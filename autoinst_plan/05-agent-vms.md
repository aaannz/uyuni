# Agent VMs — Development and E2E Guide

> How to use **agentvm** and **agentvm2** for builds, unit tests and E2E
> validation in this plan, and how to recover after a VM is recycled.
> Read this before any VM work (session protocol `00-goals.md` §1, item 6).
> General environment facts (SSH key, deployed-instance internals, XML-RPC,
> minion registration) are in `AGENTS_CUSTOM.md` §3 and `AGENTS_XMLRPC.md`.
> Verified 2026-10-22.

## 1. The two VMs

Both VMs are reserved for this plan (user decision 2026-10-22). They can
still be **recycled (restored from a snapshot) at any time without notice**,
so never rely on state left by an earlier session: run the session-start
check (§3) every time.

| | agentvm | agentvm2 |
|---|---|---|
| Address | `192.168.200.207` (`agentvm.ai.local`) | `192.168.200.217` (`agentvm2.ai.local`) |
| Role | Deployed Uyuni server: **E2E target**; builds the backend it deploys | **Build + unit tests** (pgsql-4eclipse test container); spare client host |
| OS | openSUSE Tumbleweed, SELinux enforcing | same |
| Disk | 24 GB root (≈15 GB free); a 150 GB `vdb` is attached but **unused** | 44 GB root (btrfs over `vda3` + 20 GB `vdb`) |
| Uyuni | `uyuni-server` + `uyuni-db` containers, stock 5.3.1 (`spacewalk-java-5.3.1-260400.1.2.uyuni6`, cobbler 3.3.3 **without** the `opensuse16.1` signature, PR #177) | none (only `mgradm` installed) |
| Default `java` | 21 (JDK 17 added by the check script) | 17 |

Why this split: unit tests need port 5432/80, which the deployed stack holds
on agentvm (`mgradm stop` there takes the E2E target down). On agentvm2 the
test container can stay up all session. agentvm builds what it deploys so
`deploy.sh` runs in local container mode — no SSH key has to be copied
between the VMs.

From the sandbox use the **IP addresses**; `agentvm2`/`agentvm2.ai.local`
do not resolve here. On the VMs both `.ai.local` names resolve.

```bash
VM1=192.168.200.207; VM2=192.168.200.217
SSH="ssh -i ~/workbase/id_ssh_agents -o StrictHostKeyChecking=no"
```

## 2. Network: proxy and what it lets through

All outbound traffic goes through the HTTP proxy **`http://192.168.200.1:3128`**
(`/etc/sysconfig/proxy`, `~/.m2/settings.xml`; the check script enforces
both and adds the VM addresses and `.ai.local` to `no_proxy`). There is no
NTP. Tested 2026-10-22:

| Reachable | Blocked |
|---|---|
| `download.opensuse.org`, `cdn.opensuse.org`, `ftp.gwdg.de` | every other openSUSE mirror tried (`ftp.fau.de`, `ftp.tu-chemnitz.de`, `opensuse.schlundtech.de`, `opensuse.mirror.iphh.net`, `mirror.23m.com`) |
| `registry.opensuse.org` (podman pulls) | `repo1.maven.org` |
| `repo.maven.apache.org` (Maven's default Central URL) | `github.com`, `objects.githubusercontent.com` |
| | `registry.npmjs.org` → **no frontend (`npm`) builds on the VMs** |
| sandbox ↔ VMs, VM ↔ VM (SSH, HTTPS API) | NTP (UDP 123), `pool.ntp.org` |

Consequences:

* `download.opensuse.org` 302-redirects downloads to a random mirror. The
  Uyuni OBS project happens to land on `ftp.gwdg.de`, the Leap 16.0
  distribution repo usually does not — this is what made obs-to-maven fail
  "at random" in earlier sessions (`Tunnel connection failed: 403
  Forbidden`). `prepare-java-build.sh` (§4) pins Leap to `ftp.gwdg.de`.
* Maven Central works (`repo.maven.apache.org`). The ant/ivy harness
  (`java-unittests-pgsql.sh`) still fails at `ivy-retrieve` — use Maven.
* zypper and podman work through the proxy; Tumbleweed and Uyuni repos are
  fine.

## 3. Session start: detect a recycle, restore the tooling

Signs of a recycled VM: clock days/hours off, `mvn`/`obs-to-maven` missing,
no `/root/worktree`, on agentvm a stock `rhn.jar` (August mtime) and no
`saltboot` row in `rhnKSInstallType`. Do not diagnose — just run the check
on **both** VMs; it is idempotent and finishes in seconds when nothing is
missing:

```bash
$SSH root@$VM1 'bash -s' < autoinst_plan/vm/ensure-vm-tooling.sh
$SSH root@$VM2 'bash -s' < autoinst_plan/vm/ensure-vm-tooling.sh
```

It enforces the proxy settings, sets the clock from the HTTP `Date` header
of `download.opensuse.org` (no NTP; on 2026-10-22 agentvm was 20 days and
agentvm2 2 hours behind), adds the `uyuni-utils` repo (for obs-to-maven)
and installs whatever is missing of `java-17-openjdk-devel maven
obs-to-maven rsync libxml2-tools git-core tar`, pins Maven to JDK 17 via
`/root/.mavenrc` (agentvm defaults to java 21), writes `~/.m2/settings.xml`
and prints a state summary (deployed versions, containers) — paste it into
the session record.

## 4. Scripts (`autoinst_plan/vm/`)

| Script | Runs on | Purpose |
|---|---|---|
| `ensure-vm-tooling.sh` | VM (stdin) | §3: proxy, clock, packages, Maven config |
| `sync-worktree.sh <vm-ip>` | sandbox | rsync this checkout to `/root/worktree/uyuni-autoinst_phase2` (excludes `target/`, `node_modules`, `.git`, the VM-local test `rhn.conf` and obs-to-maven output) |
| `prepare-java-build.sh` | VM (stdin) | install `uyuni-java-parent` + `branding`, run obs-to-maven with Leap pinned to `ftp.gwdg.de`, apply the servlet-API workaround (§7) |
| `reset-test-db.sh` | VM (stdin) | (re)create `uyuni-test-pgsql` from the worktree's schema (incl. `upgrade/next`); waits until ready, ~2 min |
| `deploy-branch-backend.sh` | agentvm (stdin) | branch DB deltas + `deploy.sh backend -m container` + in-container restart + jar fingerprint check (§6) |

VM scripts take an optional worktree path argument
(`'bash -s' < script.sh /root/worktree/<dir>`); the default is
`/root/worktree/uyuni-autoinst_phase2`. The VMs have no git checkout:
**re-run `sync-worktree.sh` after every local change** before building or
testing. `agentvm2:/root/worktree/uyuni` is a stale copy from an earlier
session — ignore it.

## 5. Unit tests (agentvm2)

```bash
autoinst_plan/vm/sync-worktree.sh $VM2
$SSH root@$VM2 'bash -s' < autoinst_plan/vm/prepare-java-build.sh   # first time ~3 min, then seconds
$SSH root@$VM2 'bash -s' < autoinst_plan/vm/reset-test-db.sh        # after a recycle or a schema change
$SSH root@$VM2 'cd /root/worktree/uyuni-autoinst_phase2/java/core &&
  mvn -B test -DskipTests=false -Dtest="SaltbootUtilsTest,PXEEventTest" > /root/test.log 2>&1;
  grep -E "Tests run:|BUILD|ERROR\]" /root/test.log | tail -20'
```

Validated 2026-10-22 on a freshly prepared agentvm2:
`KickstartImageTreeTest`, `SaltbootKernelOptionsBuilderTest`,
`SaltbootUtilsTest`, `PXEEventTest` — 54 tests green, sources unmodified.

* Recreate the test container (`reset-test-db.sh`) whenever
  `schema/` changed; the container builds the schema only at start.
* Real-cobbler mode: `podman exec uyuni-test-pgsql
  /manager/java/buildconf/test/cobbler/setup-cobbler-test.sh`, add
  `cobbler.port = 8080` to `java/buildconf/test/rhn.conf` on the VM, run
  Maven with `-Duse.real.cobbler=true` (details `AGENTS_CUSTOM.md` §3).
* Checkstyle runs on either VM: `mvn -B -f java checkstyle:check` (after
  `prepare-java-build.sh`).
* Long runs: start with `nohup … > /root/test.log 2>&1 &` and poll the log;
  SSH sessions can drop.

## 6. Build, deploy and E2E (agentvm)

```bash
autoinst_plan/vm/sync-worktree.sh $VM1
$SSH root@$VM1 'bash -s' < autoinst_plan/vm/prepare-java-build.sh
$SSH root@$VM1 'cd /root/worktree/uyuni-autoinst_phase2 && mvn -B -q -f java package'   # ~1 min
$SSH root@$VM1 'bash -s' < autoinst_plan/vm/deploy-branch-backend.sh
```

Status 2026-10-22: tooling, obs-to-maven and `mvn -f java package`
(spacewalk-java 5.3.3) are **validated** on agentvm from an empty `~/.m2`.
`deploy-branch-backend.sh` is **not yet validated** — its first run was
blocked by the permission classifier, so agentvm still runs the stock 5.3.1
backend. Watch its first real run closely.

What the deploy script does, and why (quirks from `AGENTS_CUSTOM.md` §3):

1. **DB deltas by hand.** The stock 5.3.1 `spacewalk-schema-upgrade` does not
   know `upgrade/next/`, and a container boot applies upgrades only when the
   schema package version changes. The script applies the versioned
   directories newer than the deployed schema (today
   `5.3.1-to-5.3.2`) once per DB (marker files in
   `/root/.branch-schema-applied/`), then all `upgrade/next/*.sql` and the
   RBAC access data (`namespace`, `accessGroup`, `endpoint`,
   `endpointNamespace`, `accessGroupNamespace` — all idempotent; without
   them SparkFilter fails at boot). The reportdb upgrades are not applied
   (not needed for autoinstallation work).
2. **No `deploy.sh -r`.** `uyuni-server.service` runs the container with
   `--rm --health-on-failure=stop`; a tomcat restart through `rctomcat` fails
   the healthcheck, podman recreates the container from the image and the
   deploy (written to `/usr/share`) is gone. The script restarts with
   `podman exec uyuni-server systemctl restart tomcat taskomatic`, which does
   not cycle the container.
3. **Fingerprint check.** `rpm -q spacewalk-java` does not see overlay
   deploys; the script compares the sha1 of `/usr/share/rhn/lib/rhn.jar` in
   the container with the build output. Re-check after any container restart
   (`podman ps` "Up" time) — a recreated container silently runs stock code
   again; then just re-run the deploy script (DB deltas are not lost, they
   live in the `uyuni-db` volume).

E2E practicalities for steps 3.4a/3.4b:

* **XML-RPC** works straight from the sandbox:
  `https://192.168.200.207/rpc/api` (self-signed — skip verification),
  admin `admin`/`admin`; harness and pitfalls in `AGENTS_XMLRPC.md`. From
  agentvm2 use `https://agentvm.ai.local/rpc/api` (`no_proxy` covers it).
* **Server-side checks:** `podman exec uyuni-server cobbler distro report` /
  `profile report` / `system report`; DB via
  `podman exec uyuni-db psql -U spacewalk -d susemanager`; logs in
  `/var/log/rhn/` inside `uyuni-server`.
* **Fixtures:** the fresh server has no channels, systems, images or image
  stores. Seed Kiwi `ImageInfo`/`ImageFile` rows and dummy files under
  `/srv/www/os-images/<org>/<name-version-revision>/` as described in
  `96-step-3.4a-saltboot-image-tree.md` §5b.5. `/srv/www`, `/var/lib/cobbler`
  and `/etc/*` are volumes (survive container recreation).
* **A minion for `pxe_update`:** register agentvm2 as a Salt minion of
  agentvm with `venv-salt-minion` (`AGENTS_CUSTOM.md` §3, "Salt Minion Test
  Registration", with `master: agentvm.ai.local`). There is no proxy system;
  a branch without a proxy yields the bare `MASTER` flag — record the
  proxy-FQDN case as a gap.
* **Disk:** ≈15 GB free on agentvm root. Before large fixtures or real
  images, ask the user about putting the unused 150 GB `vdb` to use
  (`mgr-storage-server` from `uyuni-storage-setup-server` is installed; it
  moves the container volumes, i.e. disruptive).
* **Restore** after the E2E: the server can stay on the branch build; record
  the deployed rhn.jar sha1 and the applied deltas with the results.

## 7. Known pitfalls

* **Servlet API shadowing (upstream, not branch-specific).** The OBS pom of
  `jakarta.servlet.jsp.jstl-api` 3.0.2 pulls `jakarta.servlet-api` 6.0.0
  from Central; it precedes `tomcat-servlet-api` (Servlet 6.1) and
  test-compile fails: "method does not override or implement a method from a
  supertype" at `RhnMockHttpServletResponse.java:304` and
  `SimpleTestingResponse.java:204` (`sendRedirect(String, int, boolean)`).
  CI (ant/ivy) is unaffected. `prepare-java-build.sh` overwrites the 6.0.0
  jar in the VM's `~/.m2` with the tomcat jar. Do **not** edit the test
  sources in the VM copy (earlier workaround) — the next sync reverts it.
* **SELinux:** host bind mounts into containers need `:Z`.
* **Clock:** a wrong clock breaks TLS, Maven timestamps and session expiry —
  `ensure-vm-tooling.sh` fixes it; after a recycle it is wrong again.
* **`mgradm stop` on agentvm** frees 5432/80 for a test container but takes
  the E2E server down; prefer agentvm2 for unit tests. Restore with
  `mgradm start`.
* **SLE16 PXE/cobbler:** the deployed cobbler lacks PR #177 — SLE16 distro
  creation is rejected (documented caveat, phase 1).
