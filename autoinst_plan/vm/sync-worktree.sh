#!/bin/bash
#
# Sync this checkout to /root/worktree/<checkout-name> on an agent VM.
# Run from the sandbox:  autoinst_plan/vm/sync-worktree.sh 192.168.200.207
# Build output, caches and VM-local files (test rhn.conf, obs-to-maven
# repository) are excluded, so the VM keeps them across syncs.

set -euo pipefail

VM="${1:?usage: $0 <vm-ip>}"
SRC="$(realpath "$(dirname "${BASH_SOURCE[0]}")/../..")"
DEST="/root/worktree/$(basename "$SRC")"
SSH="ssh -i $HOME/workbase/id_ssh_agents -o StrictHostKeyChecking=no"

$SSH "root@$VM" "mkdir -p '$DEST'"
rsync -az --delete -e "$SSH" \
    --exclude=.git --exclude=.venv --exclude=node_modules --exclude=target \
    --exclude=/java/repository/ --exclude=/java/.obs-to-maven-cache/ \
    --exclude=/java/buildconf/test/rhn.conf \
    "$SRC/" "root@$VM:$DEST/"
echo "synced $SRC -> $VM:$DEST"
