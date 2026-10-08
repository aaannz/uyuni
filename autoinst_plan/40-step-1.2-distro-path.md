# Step 1.2 Design — Distro Path (TreeCreateOperation / BaseTreeEditOperation)

> Implementation design for roadmap step 1.2 (`10-roadmap.md` Phase 1). The
> behavioural specification remains [`20-kernel-options.md`](20-kernel-options.md);
> this document fixes the call-site wiring design and records the
> planning-round-3 decisions that correct the round-1 spec wording.

| | |
|---|---|
| Branch | `sles16_agama` |
| Created | 2026-10-08 |
| Status | DONE (2026-10-08) |
| Related | `00-goals.md` §G0/§5.1, `10-roadmap.md` step 1.2, `20-kernel-options.md` §5.1 |

---

## 1. Research findings (corrections to round-1 spec wording)

Verified against the code on 2026-10-08; the round-1 spec (§5.1 of
`20-kernel-options.md`) is inaccurate on two points.

1. **The edit path never recomputes options today.** `TreeEditOperation` only
   overrides `getCobblerCommand()`; `BaseTreeEditOperation#store` persists
   whatever `tree.kernelOptions` holds. The spec's "effectively re-runs on
   edit" is only true indirectly: the computed options were persisted at
   create time and the Struts edit form round-trips them.
2. **XML-RPC `kickstart.tree.update` wipes computed options.** The handler
   (`java/core/src/main/java/com/redhat/rhn/frontend/xmlrpc/kickstart/tree/KickstartTreeHandler.java:294`)
   replaces `kernelOptions` with the caller's string; `install=`/`self_update=`/
   `root=live:` disappear from the DB *and* get pushed to the Cobbler distro
   like that (PXE breakage). `rename` (line 328) does not touch options.
3. **Install type is editable on edit** (`BaseTreeAction#processCommandSetters`
   sets it from the form on create *and* edit; the XML-RPC update likewise).
   Changing it today never adds the new install type's options (SLES15 → SLE16
   switch leaves the tree without `root=live:`).
4. **`serverName` is null on the XML-RPC update/rename paths.** Only create
   sets it (`ConfigDefaults.getJavaHostname()`, KickstartTreeHandler.java:164);
   the Struts form sets `request.getLocalName()` (BaseTreeAction.java:111).
   The factory throws `KernelOptionsBuilderException` on a null/empty FQDN, so
   naive edit-path wiring would break the update API.
5. **Legacy substring guards cross-match.** `contains("self_update=")` also
   matches `inst.self_update=`; `contains("root=")` matches any `*root=`
   (e.g. `nfsroot=`) — user options can spuriously suppress computed ones.
6. **Legacy `StringJoiner` leaves a leading space** when the user-typed
   options are empty (`" install=http://..."`).
7. **`TreeCreateOperationTest#testCreateRhelistro` uses rhel_7 and expects
   empty options** — it will fail after migration because of the locked
   step-1.1 deviation (`inst.repo=` unconditional for rhel/fedora/generic)
   and must be updated.

## 2. Decisions locked in this round (planning round 3)

1. **The builder merge runs on create AND edit.** It is wired into
   `BaseTreeEditOperation#store`, so `TreeCreateOperation` (via
   `super.store()`), the Struts edit path and the XML-RPC update/rename paths
   all share one call site. This fixes findings 2 and 3. Accepted behavior
   change: options the user deliberately deletes re-appear on the next
   edit/store (protective; these are computed "make it work" options).
   `TreeDeleteOperation` overrides `store()` entirely and is unaffected.
2. **FQDN fallback for a blank `serverName`:** resolve
   `ConfigDefaults.getJavaHostname()` through the same
   `InetAddress.getByName(...).getCanonicalHostName()` chain as before
   (mirrors what XML-RPC create passes). This also repairs the empty-string
   edge case, where legacy code produced a broken `install=http:///...` URL.
3. **`kernelOptionsPost` is untouched** — the legacy distro path never writes
   it, and the builder has no post-options concept in Phase 1.
4. **DB-backed tests run on the agent VM** (single shared VM, coordinate via
   `agents-workbench/autoinstallation/session-log.md`). The deployed
   `uyuni-db` lacks `opensuse16.1` in `rhnKsInstallType` (verified live
   2026-10-08) and must not be used for branch tests. When the running Uyuni
   is not needed, shut it down with **`mgradm stop`** — that frees port 5432
   so the standard `java/Makefile.docker` test-container flow works without
   port remapping.
5. **Normalizations accepted** (§4) — parsed-key checks replace substring
   checks, leading space removed; the parity tests compare modulo these.

## 3. Code design

All changes under
`java/core/src/main/java/com/redhat/rhn/manager/kickstart/tree/`.

### 3.1 `BaseTreeEditOperation`

* `getServerFqdn()` moves here from `TreeCreateOperation`, extended with the
  fallback (decision 2):

```java
protected String getServerFqdn() {
    String host = this.serverName;
    if (host == null || host.isEmpty()) {
        host = ConfigDefaults.get().getJavaHostname();
    }
    try {
        return InetAddress.getByName(host).getCanonicalHostName();
    }
    catch (UnknownHostException e) {
        return host;
    }
}
```

* New merge helper — the single home of the distro-path merge pattern
  (`20-kernel-options.md` §5.1: user-typed options win):

```java
protected void applyDistroKernelOptions() {
    KernelOptionsList options = new KernelOptionsList(this.tree.getKernelOptions());
    options.addMissingOptions(KernelOptionsBuilderFactory
            .getBuilder(this.tree.getInstallType(), getServerFqdn(), this.user)
            .distroOptions(this.tree));
    this.tree.setKernelOptions(options.toString());
}
```

* `store()`: call `applyDistroKernelOptions()` immediately before
  `KickstartFactory.saveKickstartableTree(this.tree)` — after label/basePath
  validation, before the tree is saved and before the Cobbler command store +
  `distro.setKernelOptions(...)` push, so every consumer sees the merged
  result. Coverage:
  * create: `TreeCreateOperation#store` → `super.store()`
  * edit (UI and XML-RPC update): `TreeEditOperation` via base `store()`
  * rename: base `store()` (no-op while the options are complete)
  * delete: `TreeDeleteOperation` overrides `store()` — unaffected

### 3.2 `TreeCreateOperation`

* `store()` keeps only the duplicate-label check and `super.store()`; the
  inline kernel-options block, `getSelfUpdateOption()` and `getServerFqdn()`
  are deleted (the merge happens in the base).
* Unused imports removed (`Channel`, `Optional`, `StringJoiner`,
  `InetAddress`, `UnknownHostException`).

No schema, API or UI changes; the XML-RPC `kickstart.tree.update` contract is
unchanged (its *effect* on kernel options improves, see §4).

## 4. Behavior deltas (parity register)

| Delta | Reason / notes |
|---|---|
| Edit/update re-adds missing computed options; install-type change on edit adds the new type's options | Decision 1; fixes the XML-RPC update wipe (finding 2) and the install-type-change gap (finding 3). Stale old-type options are **not** removed — add-missing only. |
| Parsed-key checks replace substring `contains()` | `nfsroot=` no longer suppresses `root=live:`; `inst.self_update=` no longer suppresses a `self_update=` add on the SLES15 path (finding 5). Bug fixes, documented. |
| No leading space when user-typed options are empty | `StringJoiner` artifact removal (finding 6). |
| `inst.repo=` for *all* rhel/fedora/generic types (incl. rhel_6/7, fedora18, generic_rpm) | Locked step-1.1 deviation (RHEL gate dropped). |
| Blank `serverName` → `ConfigDefaults.getJavaHostname()` instead of blank-host URL | Decision 2; legacy produced `install=http:///...` in that edge. |
| Distro options are merged only after base-path validation succeeds | The legacy create path set the options string *before* `validateBasePath()` ran, so a tree with an invalid initrd kept mutated options while `store()` returned an error. The merge now lives after validation; the pre-existing SUSE-type tests only passed because they ignored `store()`'s result (see §9). |
| Unknown install type → `KernelOptionsBuilderException` | Locked step-1.1 decision; unreachable for existing `rhnKSInstallType` labels (all route). |

## 5. Test plan

### 5.1 DB-backed (`TreeCreateOperationTest`, runs on the VM / CI)

* Update `testCreateRhelistro`: rhel_7 now expects `inst.repo=` (finding 7).
* Add: **user-typed options win** — pre-set a custom `install=` plus
  `useonlinerepo=1`, store, assert both survive verbatim and only the missing
  computed options are added (guards the `00-goals.md` §5.8 `useonlinerepo`
  decision).
* Add: **idempotency** — `store()` twice (create, then edit round-trip)
  yields an identical kernel-options string.
* Existing SLE16 (`root=live:` + `inst.self_update=0`, no `install=`) and
  SLES15/RHEL8 tests keep their intent.

### 5.2 DB-free (new, same test package — runs locally in the sandbox)

* Merge-semantics test hitting `applyDistroKernelOptions()` directly on a
  concrete test subclass with a mocked `KickstartableTree`/`Channel` (Mockito,
  the step-1.1 builder-test pattern): user-wins merge, SLE16 vs SLES15 vs
  RHEL routing via install type, `kernelOptionsPost` untouched.
* The `ConfigDefaults` fallback branch needs `rhn.conf` on the classpath —
  covered by the DB-backed suite only (§5.1), where the config exists.

### 5.3 Style gates (local sandbox)

```
mvn -f java/core compile
ant -f java/manager-build.xml ivy && ant -f java/manager-build.xml checkstyle
```

## 6. Verification on the agent VM

Single shared VM — record the usage window and deployed package versions in
`agents-workbench/autoinstallation/session-log.md`; check `podman ps` state
and `podman exec uyuni-server rpm -q spacewalk-java` before and after.

1. One-time toolchain: `zypper in -y maven java-17-openjdk-devel` (VM has
   Java 21 only; the build pins `<release>17</release>`; ant if checkstyle
   should run there too).
2. Sync: `rsync` the worktree → `~/worktree/uyuni-sles16_agama/`
   (deploy.sh-compatible path) plus `~/.m2/repository` and `java/repository`
   for offline dependency resolution; fallback `mvn -f java --non-recursive
   initialize` if OBS is reachable through the VM proxy.
3. **When the running Uyuni is not needed: `mgradm stop`** — frees port 5432
   so no port remapping is required (decision 4).
4. Test DB per the standard flow (`java/Makefile.docker`): from
   `~/worktree/uyuni-sles16_agama/java`,
   `cp buildconf/test/rhn.conf.postgresql-example buildconf/test/rhn.conf`
   (localhost:5432 now points at the test container), then run the
   `uyuni-master-pgsql-4eclipse` image with the worktree mounted at
   `/manager` — over ssh, run it detached (`podman run -d --name
   uyuni-test-pgsql -p 5432:5432 -v "$PWD/..":/manager
   registry.opensuse.org/systemsmanagement/uyuni/master/docker/containers/uyuni-master-pgsql-4eclipse`)
   instead of the interactive `make -f Makefile.docker` target. Verify the
   branch schema is loaded (container logs; fallback: blend the schema and
   load it with `spacewalk-sql`, see
   `susemanager-utils/testing/docker/scripts/reset_pgsql_database.sh`).
5. Run: `mvn -f java/core test -DskipTests=false
   -Dtest='TreeCreateOperationTest,TreeEditOperationTest,KernelOptions*Test,*KernelOptionsBuilder*Test'`
   with `JAVA_HOME` pointed at JDK 17.
6. Afterwards: stop/remove the test container and **`mgradm start`** to
   restore the deployed Uyuni; record results and versions in the session
   log.

E2E PXE regression stays deferred to step 1.7 — mind the cobbler PR #177
caveat (`00-goals.md` §5.1) before any SLE16 PXE attempt on the VM.

## 7. Documentation updates (implementation session)

* `10-roadmap.md`: step 1.2 → DONE + changelog entry.
* `20-kernel-options.md` §5.1: correct the "effectively re-runs on edit"
  wording (it becomes literally true), add the §4 normalization notes.
* Regenerate the presentation HTMLs: `.venv/bin/python autoinst_plan/build_html.py`.
* Session log entry in `agents-workbench/autoinstallation/session-log.md`.

## 8. Out of scope

* `KickstartCloneCommand` option copying (inventory item 12) — untouched.
* `CobblerDistroSyncCommand#backsyncKernelOptions` reverse sync (item 9;
  Phase 1 deferral stands).
* `ScheduleKickstartWizardAction`/`SSMScheduleCommand` option resolution
  (dies with the wizard, step 2.6).
* Removing stale old-type options when the install type changes (add-missing
  only; revisit under the G4 unified entity if it proves to matter).

## 9. Implementation notes (2026-10-08)

Deviations and discoveries recorded while implementing:

* **Step-1.1 defect repaired:** `RhelKernelOptionsBuilder#systemOptions`
  emitted `inst.ks=<url>`; the committed step-1.1 test and the behavioural
  spec (`20-kernel-options.md` §5.3) require `ks=<url>` (matching the legacy
  initiate path, `KickstartInitiateAction`). Fixed to `ks=`; the red
  `testSystemOptions` is green again.
* **Test fixture gap:** `TreeOperationTestBase` (via
  `KickstartTestUtils.setupTestConfiguration`) only ever created RHEL-style
  kernel/initrd files under `/tmp/kickstart/images`; SUSE install types
  resolve `/boot/x86_64/loader/*`, so every SUSE-type `store()` failed with
  `kickstart.tree.invalidinitrd`. The pre-existing SUSE tests masked this by
  ignoring `store()`'s return value and asserting the prematurely set option
  string (see the validation-order delta in §4). The test base now also
  creates the SUSE loader layout (with the org set so `isRhnTree()` is false
  and no mount-point prefix is applied).
* **Checkstyle without ant:** the sandbox and the VM lack a working
  `ant checkstyle` chain (no ant/ivy on the sandbox; Tumbleweed repos on the
  VM lack ivy, `obs-to-maven` and the toolchain). Checkstyle 10.12.7 (the
  exact version pinned by `manager-build.xml`) was run over all of
  `java/core/src` via the CLI with the same config/properties — audit clean.

## Changelog

* 2026-10-08 — created from planning round 3: merge runs on create and edit
  (`BaseTreeEditOperation#store`), `ConfigDefaults.getJavaHostname()` FQDN
  fallback, VM-based DB-backed tests with `mgradm stop` freeing port 5432,
  normalization register, DB-free/DB-backed test split.
* 2026-10-08 — implemented and verified: merge helper + FQDN fallback in
  `BaseTreeEditOperation`, `TreeCreateOperation` slimmed, `ks=` defect fix in
  the RHEL builder, SUSE tree-file fixture fix in `TreeOperationTestBase`,
  new DB-free merge test, DB-backed user-wins + idempotency tests. On the
  agent VM (`mgradm stop` → dedicated pgsql-4eclipse container with branch
  schema incl. `opensuse16.1`): 57 tests green (16 tree-op + 41
  builder/factory/list); full-core checkstyle audit clean. Status → DONE.
