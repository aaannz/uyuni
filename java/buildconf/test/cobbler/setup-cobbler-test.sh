#!/bin/bash
# Start a real Cobbler inside the Java unit-test container
# (uyuni-master-pgsql / uyuni-master-pgsql-4eclipse), configured so that the
# Java test code (which speaks the production "authentication.spacewalk"
# protocol) can log in without a running Uyuni server.
#
# Idempotent: re-running is a no-op while the stack answers. Logs land in
# /tmp/cobbler-test-*.log. See autoinst_plan/70-step-1.6-tests.md for the
# design; run the tests with -Duse.real.cobbler=true afterwards.
set -e

COBBLER_API="http://127.0.0.1/cobbler_api"
SCRIPTS_DIR="$(cd "$(dirname "$0")" && pwd)"

# Wait until the shell snippet succeeds (10 s between tries, ~3 min total).
wait_for() {
    local what="$1"
    shift
    for _ in $(seq 1 18); do
        if "$@" 2>/dev/null; then
            return 0
        fi
        sleep 10
    done
    echo "Timed out waiting for: $what"
    return 1
}

port_open() {
    python3 - "$1" <<'PY'
import socket, sys
s = socket.socket()
s.settimeout(2)
try:
    s.connect(("127.0.0.1", int(sys.argv[1])))
    sys.exit(0)
except OSError:
    sys.exit(1)
PY
}

api_login_ok() {
    python3 - "$COBBLER_API" <<'PY'
import sys
from xmlrpc.client import ServerProxy
token = ServerProxy(sys.argv[1]).login("taskomatic_user", "any-secret")
sys.exit(0 if token else 1)
PY
}

# 1. Production auth protocol: the Java side always logs in through the
#    spacewalk auth module (IntegrationService.authorize), which validates
#    credentials by calling back into a Uyuni XML-RPC API. Point that
#    endpoint at the accept-all shim.
sed -i 's|module = authentication.configfile|module = authentication.spacewalk|' /etc/cobbler/modules.conf

cat > /etc/cobbler/settings.d/zz-uyuni.settings <<EOF
# Written by setup-cobbler-test.sh (Uyuni Java unit tests).
# Note: cobbler only loads settings.d/*.settings, not *.conf.
uyuni_authentication_endpoint: http://127.0.0.1:8000
redhat_management_permissive: true
# The tests never PXE boot; disabling the tftpboot file management removes
# the background sync work (link-cache cleaning races with the per-distro
# file copies of the next test).
manage_tftpd: false
EOF

# The Java tests write their profile templates into the test kickstart dir
# (host-mounted at /tmp/kickstart), while cobbler validates the profile's
# template path inside its own template directory - link the two trees.
for sub in wizard raw; do
    if [ -d "/var/lib/cobbler/templates/$sub" ] && [ ! -L "/var/lib/cobbler/templates/$sub" ]; then
        mv "/var/lib/cobbler/templates/$sub" "/var/lib/cobbler/templates/$sub.dist"
    fi
    ln -sfn "/tmp/kickstart/$sub" "/var/lib/cobbler/templates/$sub"
done

# Cobbler 3.3.3 defect: utils.copyfile's error path calls os.path.samefile
# with a not-yet-existing destination, which masks the original error (hit
# when distro files are copied across devices, e.g. from the host-mounted
# fixture directory). Guard it - the tests never read the tftpboot copies.
python3 - <<'PY'
path = "/usr/lib/python3.13/site-packages/cobbler/utils.py"
src = open(path).read()
if "cbltest-patch" not in src:
    old = ('            raise OSError("Cannot read: %s" % src)\n'
           '        if os.path.samefile(src, dst):')
    new = ('            raise OSError("Cannot read: %s" % src)\n'
           '        try:\n'
           '            same = os.path.samefile(src, dst)\n'
           '        except OSError:\n'
           '            same = False\n'
           '        if same:  # cbltest-patch: dst may not exist yet\n')
    if old in src:
        open(path, "w").write(src.replace(old, new, 1))
        print("cobbler utils.copyfile patched")
PY
# Background full-sync tasks (triggered by the commands under test) clean
# the rendered trees while the next test's distro save copies its files -
# tolerate the resulting FileNotFoundError on the synchronous per-distro
# copy (the tests never read the tftpboot/www copies).
python3 - <<'PY'
path = "/usr/lib/python3.13/site-packages/cobbler/cobbler_collections/collection.py"
src = open(path).read()
if "cbltest-patch" not in src:
    old = ("                elif isinstance(ref, distro.Distro):\n"
           "                    self.lite_sync.add_single_distro(ref.name, rebuild_menu=rebuild_menu)")
    new = ("                elif isinstance(ref, distro.Distro):\n"
           "                    try:  # cbltest-patch: background syncs race with concurrent saves/removals\n"
           "                        self.lite_sync.add_single_distro(ref.name, rebuild_menu=rebuild_menu)\n"
           "                    except (FileNotFoundError, OSError):\n"
           "                        pass")
    if old in src:
        open(path, "w").write(src.replace(old, new, 1))
        print("cobbler collection.add patched")
PY
# restart cobblerd to pick up the patches (started below when not running)
if port_open 25151; then
    kill "$(pgrep cobblerd)" 2>/dev/null || true
    sleep 2
fi

# 2. The SSL vhost aborts apache startup (deployment-time cert missing).
if [ -f /etc/apache2/vhosts.d/vhost-ssl.conf ]; then
    mv /etc/apache2/vhosts.d/vhost-ssl.conf /etc/apache2/vhosts.d/vhost-ssl.conf.disabled
fi

# 3. Expose /cobbler_api beyond localhost: the tests connect from the VM
#    host through the published port (same approach as the acceptance
#    suite's cobbler_test.rb).
cat > /etc/apache2/conf.d/zz-cobblerapi-test.conf <<'EOF'
# Written by setup-cobbler-test.sh (Uyuni Java unit tests).
<Location "/cobbler_api">
    Require ip 127.0.0.1 ::1 10.0.0.0/8 172.16.0.0/12 192.168.0.0/16
</Location>
EOF

# 4. Auth shim emulating the Uyuni authentication API.
if ! port_open 8000; then
    python3 "${SCRIPTS_DIR}/cobbler-auth-shim.py" > /tmp/cobbler-test-shim.log 2>&1 &
fi

# 5. Cobbler daemon (file-based collections storage, no mongodb needed).
if ! port_open 25151; then
    cobblerd > /tmp/cobbler-test-cobblerd.log 2>&1 &
fi

# 6. Apache serves /cobbler_api (ProxyPass to cobblerd :25151; cobblerd
#    itself is path-sensitive and would 404 the /cobbler_api path).
if port_open 80; then
    # pick up the changed configuration
    /usr/sbin/start_apache2 -k restart >> /tmp/cobbler-test-apache.log 2>&1
else
    /usr/sbin/start_apache2 -k start > /tmp/cobbler-test-apache.log 2>&1
fi

# 7. Smoke check: a login like the one the Java tests perform.
wait_for "cobbler api login" api_login_ok
echo "Cobbler test stack up, login ok."