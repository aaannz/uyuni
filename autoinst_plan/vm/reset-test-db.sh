#!/bin/bash
#
# (Re)create the pgsql-4eclipse unit-test container with the worktree's schema.
# Run as root ON THE VM (agentvm2 by default; on agentvm run `mgradm stop`
# first — the deployed stack holds :5432 and :80):
#   ssh root@<vm> 'bash -s' < autoinst_plan/vm/reset-test-db.sh [worktree]
# The container's own init script builds the branch schema (incl.
# schema/spacewalk/upgrade/next) and the reportdb, then runs postgres in the
# foreground. A recreate is the only reliable reset: re-running
# reset_pgsql_database.sh in a used container aborts half way.

set -euo pipefail

WT="${1:-/root/worktree/uyuni-autoinst_phase2}"
NAME="uyuni-test-pgsql"
IMAGE="registry.opensuse.org/systemsmanagement/uyuni/master/docker/containers/uyuni-master-pgsql-4eclipse"

log() { echo "[reset-test-db] $*"; }

if [ ! -f "$WT/java/buildconf/test/rhn.conf" ]; then
    cp "$WT/java/buildconf/test/rhn.conf.postgresql-example" "$WT/java/buildconf/test/rhn.conf"
    log "created java/buildconf/test/rhn.conf from the postgresql example"
fi

podman rm -f "$NAME" > /dev/null 2>&1 || true
mkdir -p /tmp/kickstart /tmp/opt
# :Z is mandatory — SELinux is enforcing on the VMs.
podman run -d --name "$NAME" -p 5432:5432 -p 8080:80 \
    -v "$WT:/manager:Z" -v /tmp/kickstart:/tmp/kickstart:Z -v /tmp/opt:/tmp/opt:Z \
    "$IMAGE" > /dev/null
log "started $NAME on $WT, waiting for the schema init (several minutes)"

for _ in $(seq 1 90); do
    if ! podman ps --format '{{.Names}}' | grep -qx "$NAME"; then
        podman logs --tail 40 "$NAME"
        log "ERROR: container exited during init (log tail above)"
        exit 1
    fi
    if ! podman exec "$NAME" pgrep -f 'spacewalk-schema-upgrade|build-.*schema|pg_ctl' > /dev/null \
        && podman exec "$NAME" su - postgres -c "psql -d reportdb -Atc 'select 1'" > /dev/null 2>&1 \
        && [ "$(podman exec "$NAME" su - postgres -c "psql -d susemanager -Atc 'select count(*) from web_customer'" 2> /dev/null)" = 1 ]; then
        log "ready: $(podman exec "$NAME" su - postgres -c "psql -d susemanager -Atc 'select label from rhnKSInstallType order by 1'" | paste -sd ' ')"
        exit 0
    fi
    sleep 10
done
log "ERROR: init did not finish within 15 minutes"
exit 1
