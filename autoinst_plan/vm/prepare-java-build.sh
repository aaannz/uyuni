#!/bin/bash
#
# Prepare a synced worktree on an agent VM for Maven builds/tests.
# Run as root ON THE VM (after ensure-vm-tooling.sh):
#   ssh root@<vm> 'bash -s' < autoinst_plan/vm/prepare-java-build.sh [worktree]
# Default worktree: /root/worktree/uyuni-autoinst_phase2
#
# Why not just `mvn -f java initialize`: obs-to-maven downloads the Leap 16.0
# repodata from download.opensuse.org, which redirects to a random mirror;
# the VM proxy allows only ftp.gwdg.de, so the stock run fails at random
# ("Tunnel connection failed: 403 Forbidden"). This script runs obs-to-maven
# with a VM-local config copy pointing Leap at ftp.gwdg.de and writes the
# `last-execution` stamp, so Maven's initialize step skips its own run.

set -euo pipefail

WT="${1:-/root/worktree/uyuni-autoinst_phase2}"
CONFIG="$WT/java/buildconf/ivy/obs-maven-config.yaml"
VM_CONFIG="/root/obs-maven-config.vm.yaml"
REPO_DIR="$WT/java/repository/uyuni"
STAMP="$REPO_DIR/last-execution"

log() { echo "[prepare-java] $*"; }

cd "$WT"

log "installing uyuni-java-parent and branding into ~/.m2"
mvn -B -q -f microservices/uyuni-java-parent --non-recursive install
mvn -B -q -f branding install

if [ -f "$STAMP" ] && [ "$STAMP" -nt "$CONFIG" ] && [ "${FORCE:-0}" != 1 ]; then
    log "obs-to-maven repository up to date ($(cat "$STAMP"))"
else
    sed 's|https://download.opensuse.org/distribution/|https://ftp.gwdg.de/pub/opensuse/distribution/|' \
        "$CONFIG" > "$VM_CONFIG"
    mkdir -p "$REPO_DIR"
    log "running obs-to-maven (Leap via ftp.gwdg.de)"
    obs-to-maven -p -c "$WT/java/.obs-to-maven-cache" "$VM_CONFIG" "$REPO_DIR"
    date +"Dependencies were last updated on %Y-%m-%d %H:%M:%S" > "$STAMP"
fi
log "obs-to-maven jars: $(find "$REPO_DIR" -name '*.jar' | wc -l) (config lists $(grep -c '^  - artifact:' "$CONFIG"))"

# jakarta.servlet.jsp.jstl-api 3.0.2 (OBS pom) pulls jakarta.servlet-api 6.0.0
# from Maven Central; it precedes tomcat-servlet-api (Servlet 6.1) on the
# classpath and test-compile fails on the 6.1-only
# sendRedirect(String, int, boolean) @Overrides in RhnMockHttpServletResponse
# and SimpleTestingResponse. Shadow it with the tomcat jar (VM-local ~/.m2 only;
# `rm -rf ~/.m2/repository/jakarta/servlet/jakarta.servlet-api` undoes it).
log "resolving java/core dependencies"
mvn -B -q -f java/core dependency:resolve > /dev/null
SERVLET_JAR=/root/.m2/repository/jakarta/servlet/jakarta.servlet-api/6.0.0/jakarta.servlet-api-6.0.0.jar
TOMCAT_JAR=$(ls /root/.m2/repository/org/apache/tomcat/tomcat-servlet-api/*/tomcat-servlet-api-*[0-9].jar | sort -V | tail -1)
if [ -f "$SERVLET_JAR" ] && ! cmp -s "$SERVLET_JAR" "$TOMCAT_JAR"; then
    cp "$TOMCAT_JAR" "$SERVLET_JAR"
    rm -f "$SERVLET_JAR.sha1"
    log "shadowed jakarta.servlet-api 6.0.0 with $(basename "$TOMCAT_JAR")"
fi
