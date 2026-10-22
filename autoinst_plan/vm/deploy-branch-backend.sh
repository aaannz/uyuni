#!/bin/bash
#
# Deploy the branch backend (built with `mvn -f java package`) into the
# uyuni-server container on agentvm, including the branch DB deltas.
# Run as root ON agentvm:
#   ssh root@192.168.200.207 'bash -s' < autoinst_plan/vm/deploy-branch-backend.sh [worktree]
#
# Encodes the AGENTS_CUSTOM.md §3 deploy quirks:
# * no `deploy.sh -r` — its rctomcat restart fails the healthcheck and podman
#   recreates the container from the image (deploy lost); services are
#   restarted with systemctl inside the container instead;
# * the stock image's spacewalk-schema-upgrade predates `upgrade/next/`, and a
#   container boot never applies branch scripts — they are applied by hand
#   (versioned scripts once per DB, next/ + RBAC access data every time —
#   both idempotent);
# * the deploy is verified by comparing the jar in the container with the
#   build output (rpm -q is blind to overlay deploys).

set -euo pipefail

WT="${1:-/root/worktree/uyuni-autoinst_phase2}"
SCHEMA="$WT/schema/spacewalk"
MARKER_DIR=/root/.branch-schema-applied

log() { echo "[deploy-backend] $*"; }
db() { podman exec -i uyuni-db psql -U spacewalk -d susemanager -q -v ON_ERROR_STOP=1 "$@"; }
apply_sql() {
    log "  sql: ${1#"$SCHEMA"/}"
    db -1 -f - < "$1" > /dev/null
}

BUILT_JAR=$(ls "$WT"/java/webapp/target/webapp-*/WEB-INF/lib/core-*.jar 2> /dev/null | head -1)
[ -n "$BUILT_JAR" ] || { log "ERROR: no build output, run 'mvn -f java package' first"; exit 1; }

deployed=$(podman exec uyuni-server rpm -q --qf '%{version}' susemanager-schema)
log "deployed schema package: $deployed"

# Versioned migrations newer than the deployed schema, once per DB.
mkdir -p "$MARKER_DIR"
for dir in $(ls -d "$SCHEMA"/upgrade/susemanager-schema-*-to-susemanager-schema-* | sort -V); do
    from=$(basename "$dir" | sed 's/^susemanager-schema-\(.*\)-to-susemanager-schema-.*$/\1/')
    if [ "$(printf '%s\n' "$deployed" "$from" | sort -V | head -1)" = "$deployed" ] \
        && [ ! -f "$MARKER_DIR/$(basename "$dir")" ]; then
        log "applying $(basename "$dir")"
        for f in $(ls "$dir"/*.sql | sort -V); do apply_sql "$f"; done
        touch "$MARKER_DIR/$(basename "$dir")"
    fi
done

log "applying upgrade/next and RBAC access data"
for f in $(ls "$SCHEMA"/upgrade/next/*.sql 2> /dev/null | sort -V); do apply_sql "$f"; done
for f in namespace accessGroup endpoint endpointNamespace accessGroupNamespace; do
    apply_sql "$SCHEMA/common/data/$f.sql"
done

log "deploying webapp + jars (deploy.sh, container mode, no restart)"
"$WT/java/scripts/maven/deploy.sh" backend -m container

log "restarting tomcat and taskomatic inside the container"
podman exec uyuni-server systemctl restart tomcat taskomatic

for _ in $(seq 1 30); do
    code=$(curl -sk -o /dev/null -w '%{http_code}' -m 10 https://localhost/rpc/api \
        -H 'Content-Type: text/xml' \
        --data '<?xml version="1.0"?><methodCall><methodName>api.getVersion</methodName></methodCall>' || true)
    [ "$code" = 200 ] && break
    sleep 10
done
[ "$code" = 200 ] || { log "ERROR: API not answering (last HTTP $code); see /var/log/tomcat/localhost.log in the container"; exit 1; }

built=$(sha1sum < "$BUILT_JAR" | cut -d' ' -f1)
running=$(podman exec uyuni-server sha1sum /usr/share/rhn/lib/rhn.jar | cut -d' ' -f1)
if [ "$built" != "$running" ]; then
    log "ERROR: deployed rhn.jar differs from the build (container recreated?)"
    exit 1
fi
log "OK: API up, rhn.jar in the container matches the build ($built)"
