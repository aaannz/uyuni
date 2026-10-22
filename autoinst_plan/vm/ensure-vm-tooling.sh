#!/bin/bash
#
# Idempotent session-start check for the agent VMs (agentvm, agentvm2).
# Run as root ON THE VM:
#   ssh root@<vm> 'bash -s' < autoinst_plan/vm/ensure-vm-tooling.sh
# Safe to re-run at any time; it only adds what is missing.
# See autoinst_plan/05-agent-vms.md for the surrounding workflow.

set -euo pipefail

PROXY_URL="http://192.168.200.1:3128"
NO_PROXY_LIST="localhost,127.0.0.1,192.168.200.1,192.168.200.207,192.168.200.217,.ai.local"
JDK17_HOME="/usr/lib64/jvm/java-17-openjdk"
UYUNI_UTILS_REPO="http://download.opensuse.org/repositories/systemsmanagement:/Uyuni:/Utils/openSUSE_Tumbleweed/"
PACKAGES=(java-17-openjdk-devel maven obs-to-maven rsync libxml2-tools git-core tar)

log() { echo "[ensure-vm] $*"; }

# 1. Proxy: zypper reads /etc/sysconfig/proxy; login shells export it.
sed -i \
    -e "s|^PROXY_ENABLED=.*|PROXY_ENABLED=\"yes\"|" \
    -e "s|^HTTP_PROXY=.*|HTTP_PROXY=\"${PROXY_URL}\"|" \
    -e "s|^HTTPS_PROXY=.*|HTTPS_PROXY=\"${PROXY_URL}\"|" \
    -e "s|^NO_PROXY=.*|NO_PROXY=\"${NO_PROXY_LIST}\"|" \
    /etc/sysconfig/proxy
export http_proxy="$PROXY_URL" https_proxy="$PROXY_URL" HTTP_PROXY="$PROXY_URL" HTTPS_PROXY="$PROXY_URL"
export no_proxy="$NO_PROXY_LIST" NO_PROXY="$NO_PROXY_LIST"
log "proxy: $PROXY_URL (no_proxy: $NO_PROXY_LIST)"

# 2. Clock: no NTP reaches the VMs; a restored snapshot can be days behind.
http_date=$(curl -sI -m 20 https://download.opensuse.org/ | sed -n 's/^[Dd]ate: //p' | tr -d '\r')
if [ -n "$http_date" ]; then
    date -u -s "$http_date" > /dev/null
    hwclock -w 2> /dev/null || true
    log "clock set from HTTP Date: $(date -u)"
else
    log "WARNING: could not read the HTTP Date header, clock unchanged: $(date -u)"
fi

# 3. Packages (obs-to-maven lives in the Uyuni Utils repo).
if ! zypper -q lr -u | grep -q "Uyuni:/Utils/openSUSE_Tumbleweed"; then
    zypper -q ar -f "$UYUNI_UTILS_REPO" uyuni-utils
    zypper -q --gpg-auto-import-keys ref uyuni-utils
    log "added repo uyuni-utils"
fi
missing=()
for p in "${PACKAGES[@]}"; do
    rpm -q "$p" > /dev/null 2>&1 || missing+=("$p")
done
if [ ${#missing[@]} -gt 0 ]; then
    log "installing: ${missing[*]}"
    zypper -q --gpg-auto-import-keys -n in --no-recommends "${missing[@]}"
fi
log "packages present: ${PACKAGES[*]}"

# 4. Maven: JDK 17 regardless of the default java (21 on agentvm), proxy for central.
echo "JAVA_HOME=${JDK17_HOME}" > /root/.mavenrc
mkdir -p /root/.m2
cat > /root/.m2/settings.xml << EOF
<settings>
  <proxies>
    <proxy>
      <id>agentproxy</id>
      <active>true</active>
      <protocol>http</protocol>
      <host>192.168.200.1</host>
      <port>3128</port>
      <nonProxyHosts>127.0.0.1|localhost|*.ai.local|192.168.200.*</nonProxyHosts>
    </proxy>
  </proxies>
</settings>
EOF
log "maven: $(mvn -v 2>/dev/null | sed -n 's/^\(Apache Maven [^ ]*\).*/\1/p; s/^Java version: \([^,]*\).*/java \1/p' | paste -sd ' ')"

# 5. Worktree target directory (the VM has no checkout; agents rsync into it).
mkdir -p /root/worktree
log "worktree dir: /root/worktree ($(ls /root/worktree | paste -sd ' '))"

# 6. State summary for the session record.
log "host: $(hostname -f), selinux: $(getenforce 2> /dev/null || echo n/a), free on /: $(df -h --output=avail / | tail -1 | tr -d ' ')"
if podman ps --format '{{.Names}}' | grep -qx uyuni-server; then
    log "uyuni-server: $(podman exec uyuni-server rpm -q spacewalk-java cobbler | paste -sd ' ')"
fi
log "containers: $(podman ps -a --format '{{.Names}}={{.Status}}' | paste -sd ' ')"
