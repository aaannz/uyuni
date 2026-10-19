# Step 1.7 Design — Cleanup & Verification (Phase 1 close-out)

> Implementation design for roadmap step 1.7 (`10-roadmap.md` Phase 1): remove
> dead code left behind by the steps-1.1–1.5 migrations, checkstyle, and the
> E2E regression matrix per `20-kernel-options.md` §8 on the agent VM.

| | |
|---|---|
| Branch | `sles16_agama` |
| Created | 2026-10-12 |
| Status | DONE (2026-10-12) |
| Related | `00-goals.md` §1 (session protocol/VM), §4 (guardrails), `10-roadmap.md` step 1.7, `20-kernel-options.md` §8/§9, `70-step-1.6-tests.md` (real-cobbler mode recipe) |

---

## 1. Research findings (verified against the tree, 2026-10-12)

The legacy string-building code was **already deleted** during steps 1.2–1.4
(commits `c87a62680fc`, `f4f4a5e18d7`): the `TreeCreateOperation` inline
builders, the `KickstartInitiateAction` hand-rolled map serializer + the
debian/ubuntu and rhel6 `kssendmac` branches, and the suse-breed-gated
`CobblerSystemCreateCommand` logic are gone. What remained is a small tail of
unused API surface, verified by repo-wide grep (zero callers in main **and**
test):

| Candidate | Location | Kind |
|---|---|---|
| `KernelOptionsList.isEmpty()` | `KernelOptionsList.java:329` | migration-born, fully dead |
| `KernelOptionsBuilderException(String,Throwable)` | `KernelOptionsBuilderException.java:32` | migration-born, fully dead (all throws use the 1-arg ctor) |
| 1-arg ctors `Agama/AutoYast/RhelKernelOptionsBuilder(String)` | `Agama:32`, `AutoYast:30`, `Rhel:29` | migration-born, fully dead (factory + tests use the 2-arg ctors) |
| `BaseTreeEditOperation.replaceLegacyPackageNames(List)` | `BaseTreeEditOperation.java:250` | pre-existing dead (lost its last caller pre-branch, maven migration) |
| `BaseTreeEditOperation.getServerName()` | `BaseTreeEditOperation.java:211` | pre-existing dead getter (setter is used) |
| `KernelOptionsList.setOptionOrReplace` / `applyOverrides` | `:174` / `:199` | test-only (`KernelOptionsListTest`) |
| `BaseTreeEditOperation.KICKSTART_CAPABILITY` | `:54` | test-only (`PackageManagerTest:462,472`) |

Deliberately **kept**:

* `KernelOptionsList.getValue` — test-only today but a deliberate step-1.3
  addition (spec §1 starting-point API, part of the G6-ready container).
* `KickstartData.LEGACY_KICKSTART_PACKAGE_NAME` — other live callers
  (`ChannelManager`), unaffected by removing the dead method.
* All deferred §6.3 sites (schedule/SSM/wizard option resolution, saltboot PXE
  maps, `CobblerDistroSyncCommand#backsync`, `KickstartCloneCommand`,
  `DownloadFile` `useonlinerepo`, `CobblerDistroHelper`, `KickstartTreeHandler`)
  — confirmed intact with their legacy logic, untouched per plan.

## 2. Decisions locked this round (planning round 7)

1. **Cleanup scope A+B+C** (user decision 2026-10-12): remove all seven
   candidates above, including the test-only members. `PackageManagerTest`
   switches to the literal `"rhn.kickstart.boot_image"`;
   `KernelOptionsListTest` loses the `setOptionOrReplace`/`applyOverrides`
   cases (their semantics live on in `setOption(key, value, true)` tests).
   `getValue` stays.
2. **E2E matrix rows are tested for real, not documented** — fake content
   through the real flow: custom base channels via XML-RPC, dummy
   kernel/initrd layouts on the `uyuni-server` container filesystem (exact
   expected names — `boot/x86_64/loader/{linux,initrd}` for SUSE,
   `images/pxeboot/{vmlinuz,initrd.img}` for RHEL), then the real
   `kickstart.tree.create` / profile upload / `kickstart.system.scheduleKickstart`
   flow against the deployed server; assertions on DB
   (`rhnKickstartableTree.kernel_options`) **and** cobbler records. Phase 1
   changes option strings, not media handling, so dummy media is sufficient
   and any accidental boot is harmless.
3. **SLE16 PXE on the deployed VM**: cobbler lacks PR #177 (no `opensuse16`
   signature; user-confirmed: PRs merged upstream, release will sync with the
   code release — the deployed VM simply predates it). The SLE16 tree-create
   row is **attempted and its rejection recorded as evidence**, then the
   documented caveat applies; unit-level parity evidence from step 1.6 (mock
   mode green, real-mode skips) stands. No signature backport on the VM.
4. **Salt initiate row (SLES15)** safety protocol: the `bootloader.autoinstall`
   state ends in `shutdown -r +1` — capture the composed `kopts` pillar from
   the salt job-publish event (`salt-run state.event`), then immediately
   `shutdown -c`, cancel the kickstart session, and restore the boot
   configuration (remove `/etc/grub.d/42_uyuni_reinstall`, restore
   `GRUB_DEFAULT`, `grub2-mkconfig`). Scheduled last in the VM window.
5. **Backend deployment** to the VM (`deploy.sh backend`: tomcat + taskomatic)
   is required for the matrix to exercise branch code; deployed versions
   recorded before/after per the guardrail-8 protocol.
6. `java/core/pom.xml` (uncommitted servlet-API exclusion from an earlier
   session) stays uncommitted — local build fix, noted in the session log.

## 3. Execution plan

1. Step doc + roadmap status (this document).
2. Dead-code removal (§2 decision 1) → `mvn -f java compile
   checkstyle:checkstyle` + DB-free suites locally.
3. VM window: pre-flight (clock, deployed versions, PR #177 signature check,
   `rhnKsInstallType` rows — seed `opensuse16.1` from the upgrade SQL if
   missing) → backend deploy → matrix rows (SLES15 tree+profile+schedule,
   RHEL7 tree, SLE16 attempt, salt initiate) → §9.3 mock-mode regression map +
   real-cobbler mode (1.6 recipe) → restore deployed stack, record versions.
4. Wrap-up: results recorded here, roadmap status → DONE + changelog, session
   log, HTML regeneration.

## 4. Results (2026-10-12)

### 4.1 Dead-code cleanup (scope A+B+C)

Removed (all verified zero-caller before deletion):

* `KernelOptionsList.isEmpty()` / `setOptionOrReplace` / `applyOverrides`
  (`getValue` kept — deliberate step-1.3 addition). `KernelOptionsListTest`
  lost the applyOverrides case; the setOption-replace case now drives the
  3-arg `setOption(key, value, true)` directly.
* `KernelOptionsBuilderException(String, Throwable)`.
* 1-arg ctors of `Agama`/`AutoYast`/`RhelKernelOptionsBuilder`.
* `BaseTreeEditOperation.replaceLegacyPackageNames` (+ `EMPTY_STRING`,
  `KickstartData`/`PackageListItem` imports — `LEGACY_KICKSTART_PACKAGE_NAME`
  itself stays, it has live callers in `ChannelManager`) and the dead
  `getServerName()` getter.
* `BaseTreeEditOperation.KICKSTART_CAPABILITY` — `PackageManagerTest` now
  declares the `"rhn.kickstart.boot_image"` literal itself.

Local sandbox: `mvn -f java compile checkstyle:checkstyle` clean; DB-free
suites green (the only failing local test, `KickstartInstallTypeTest
#testKsInstallType`, needs a DB and runs on the VM).

### 4.2 Agent-VM session (window ~09:40–11:05 CEST 2026-10-12)

**Deployment learnings** (recorded in `AGENTS_CUSTOM.md` §3):

* `uyuni-server.service` runs `podman run --rm` with `Restart=on-success`,
  `ExecStartPre=podman rm --force` and **`--health-on-failure=stop`**: any
  clean PID1 exit recreates the container and discards the writable layer.
  The login-page healthcheck (30 s interval, 3 retries) fails during every
  tomcat restart, so the deploy script's `-r` path (rctomcat/rctaskomatic)
  always cycles the container and wipes the freshly deployed webapp. A
  temporary unit drop-in redefining `ExecStart` with
  `--health-on-failure=none` made deploys + direct
  `podman exec uyuni-server systemctl restart tomcat|taskomatic` safe
  (verified: container survives, canary persists). Drop-in removed after the
  session.
* First backend deploy of the branch (`deploy.sh backend`) also required the
  branch's unapplied DB deltas: the deployed 2026.08 stack reports the same
  schema package version as the branch, so `91-schema-update.sh` skips
  them. Applied manually: `schema/spacewalk/upgrade/next/` (001, 100, 101,
  500, 520, 540, 550) and, after the RBAC boot validator demanded them, the
  fresh-install access data (`namespace.sql`, `accessGroup.sql`,
  `endpoint.sql`, `endpointNamespace.sql`, `accessGroupNamespace.sql` — all
  idempotent `ON CONFLICT DO NOTHING`; re-run duplicate-key errors are
  benign). Without this, SparkFilter fails at boot (`transactional_mode does
  not exist`, then `RbacRouteValidationException` for 5 branch endpoints).
* `opensuse16.1` seeded into `rhnKsInstallType` (id 13) from the branch
  upgrade SQL; visible via `kickstart.tree.listInstallTypes`.

**E2E regression matrix** (§8 rows; fake content through the real flow —
custom base channels + dummy kernel/initrd layouts at `/srv/www/e2e17-*`
inside the container):

| Row | Result | Evidence |
|---|---|---|
| SLES15 tree + profile + schedule | PASS | `kickstart.tree.create` with **empty** kopts → DB `install=http://agentvm.ai.local/ks/dist/e2e17-sles15 self_update=0`, cobbler distro identical; `kickstart.createProfile` → cobbler profile kopts `<<inherit>>`; `system.provisionSystem` (action 7) → cobbler system record `install=http://agentvm.ai.local/ty/6agQ3w1P` (session tinyurl — the step-1.3 media override) with `kernel_options_post` `<<inherit>>` |
| RHEL7 tree | PASS | `inst.repo=http://agentvm.ai.local/ks/dist/e2e17-rhel7` (DB + cobbler distro) |
| SLE16 tree | documented caveat | cobbler rejects the distro save: `os_version for breed "suse" must be one of ... given was "opensuse16.1"` — the deployed VM cobbler lacks PR #177 (user-confirmed: PRs merged, release will sync with the code release). Unit-level parity evidence from step 1.6 stands (mock-mode exact-string tests green, real-mode fixtures skip) |
| Salt initiate (SLES15) | PASS | minion `victim01` (venv-salt-minion) → `state.apply bootloader.autoinstall` pillar captured via `salt-run state.event`: `kopts = "self_update=0 install=http://agentvm.ai.local/ty/6agQ3w1P autoyast=http://agentvm.ai.local/cblr/svc/op/autoinstall/system/victim01:1 info=http://agentvm.ai.local/cblr/svc/op/nopxe/system/victim01:1"` with kernel/initrd from the tree saltfs — the complete step-1.4 composition (resolved options incl. the media override, installer selection, nopxe signaling; textmode no-op without `text`; no `initrd` param). The `shutdown -r +1` step never ran (no grub on the VM host — requisite failure, expected and harmless); action 7 recorded as failed with the full salt result in `rhnserveraction.result_msg` |

Artifacts: `tasklist/validation/e2e17_matrix_rows.py`,
`tasklist/validation/e2e17_provision.py`,
`tasklist/validation/artifacts/e2e17_matrix_rows.json`,
`tasklist/validation/artifacts/e2e17_provision.json`.

### 4.3 Test suites on the VM (fresh `uyuni-test-pgsql`, branch schema)

* Mock mode (§9.3 regression map + `PackageManagerTest`): **180 tests, 0
  failures, BUILD SUCCESS**.
* Real-cobbler mode (`-Duse.real.cobbler=true`, cobbler 3.3.3 in the test
  container): **55 run, 50 green, 5 skipped** (SLE16, PR #177) — identical
  to the step-1.6 baseline. One environmental flake: the first real-mode
  run wedged inside `cleanUpCobbler` → `Profile.invokeRemove` (cobblerd
  never answered the remove; redstone has no read timeout). Recovered by
  recreating the test container; the second run was fully green. Pre-existing
  container flake — step 1.7 touched no test-infrastructure code.

### 4.4 Stack restored

* Test container removed, `mgradm start`, healthcheck drop-in removed →
  stock container from the image (deploy overlays are ephemeral by design).
  `spacewalk-java-5.3.1-260400.1.2.uyuni6.noarch` +
  `cobbler-3.3.3-260400.41.1.uyuni6.noarch` unchanged, login page 200,
  tomcat/taskomatic active.
* DB remnants on the (recycled) VM: branch next-upgrade deltas + access
  data applied, `opensuse16.1` install type, the `e2e17-*` fixture
  channels/trees/profile, action 7 and minion `victim01`.

## Changelog

* 2026-10-12 — created from planning round 7: dead-code inventory verified
  against the tree (legacy builders already deleted in 1.2–1.4), scope A+B+C
  locked, E2E matrix protocol fixed (fake-content/real-flow rows, SLE16
  attempt-and-record, initiate safety protocol, backend deploy).
* 2026-10-12 — implemented and verified: cleanup (7 members removed),
  checkstyle clean, 180 mock-mode tests + 50/55 real-mode tests green on the
  agent VM, E2E matrix executed (SLES15/RHEL7/salt-initiate PASS, SLE16
  documented PR #177 caveat with recorded cobbler rejection). Deployment
  quirks recorded in `AGENTS_CUSTOM.md` §3 (health-on-failure=stop container
  cycling, deploy.sh -r path, branch DB deltas on a version-matched stack).
  Stack restored. Status → DONE — **Phase 1 complete**.
