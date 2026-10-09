# Step 1.5 Design — Profile Path (CobblerProfileCommand#updateCobblerFields)

> Implementation design for roadmap step 1.5 (`10-roadmap.md` Phase 1). The
> behavioural specification remains [`20-kernel-options.md`](20-kernel-options.md);
> this document fixes the call-site wiring design and records the
> planning-round-4 decisions taken for this step.

| | |
|---|---|
| Branch | `sles16_agama` |
| Created | 2026-10-08 |
| Status | DONE (2026-10-08) |
| Related | `00-goals.md` §G0/§5.1, `10-roadmap.md` step 1.5, `20-kernel-options.md` §5.4, `30-step-1.1-builders.md` §1.4 (decision 4) |

---

## 1. Research findings (verified against the code, 2026-10-08)

1. **Profile-level kernel options have exactly one writer.**
   `KickstartDetailsEditAction#processCobblerFormValues`
   (`java/core/src/main/java/com/redhat/rhn/frontend/action/kickstart/KickstartDetailsEditAction.java:366`)
   sets both `kernelOptions` and `postKernelOptions` from the profile details
   form onto a `CobblerProfileEditCommand` and calls `store()`. No other code
   path sets kernel options on a `CobblerProfileCommand`.
2. **The convergence point is `CobblerProfileCommand#updateCobblerFields`**
   (`java/core/src/main/java/com/redhat/rhn/manager/kickstart/cobbler/CobblerProfileCommand.java:77`):
   `if (kernelOptions != null) profile.setKernelOptions(Optional.of(kernelOptions));`
   — the raw string is pushed to the cobbler profile verbatim. Both
   `CobblerProfileEditCommand#store` and `CobblerProfileCreateCommand#store`
   route through it, so create and edit share one site.
3. **Every other `updateCobblerFields` caller leaves `kernelOptions` null** and
   therefore skips the block entirely: taskomatic `KickstartFileSyncTask` /
   `CobblerSyncTask`, `CobblerProfileSyncCommand#createProfile`,
   `CobblerDistroCreateCommand`, `KickstartWizardHelper` (profile create),
   `BaseKickstartCommand#store`, XML-RPC `ProfileHandler#setKickstartTree`,
   `KickstartSoftwareEditAction`. Wiring inside the `if` block cannot affect
   them. (The `setKernelOptions` hits in `KickstartScheduleCommand`,
   `SSMScheduleCommand`, `SystemHandler`, `ScheduleKickstartWizardAction` are
   on the *system* path — steps 1.3 / 2.6 — not on profile commands.)
4. **Round-trip semantics.** Cobbler stores kernel options as a dict; a string
   setter value is parsed cobbler-side (shlex-like). The UI form round-trip
   renders the cobbler dict via `CobblerObject.convertOptionsMap` into the
   text field, showing `CobblerObject.INHERIT_KEY` (`<<inherit>>`) when the
   profile has no options. `KernelOptionsList` parsing keeps quote characters
   inside tokens, so `key="a b"` survives parse→serialize unchanged;
   `<<inherit>>` and the empty string round-trip identically.
5. **Routing key exists:** `KickstartData#getInstallType()`
   (`java/core/src/main/java/com/redhat/rhn/domain/kickstart/KickstartData.java:823`)
   resolves tree → install type, null-safe. `CobblerProfileCommand` carries an
   optional `user` (null for the taskomatic constructor) — matching the §5.5
   user-context semantics of the installer-updates lookup.
6. **Test infra:** `CobblerCommandTestBase` defaults to a *mocked* cobbler
   connection (the connection class is resolved from config; the test
   `rhn.conf` maps it to the mock), so `CobblerCommandTest` profile tests run
   DB-backed without a live cobbler — the same way step 1.2's tree-operation
   tests ran on the agent VM.
7. **No kickstart-host concept exists on the profile path** (no request
   context, no operation `serverName`). `profileOptions` is empty today so the
   FQDN is unused — but the factory validates it non-empty, and G6 will need a
   real value.

## 2. Decisions locked in this round (planning round 4)

1. **`postKernelOptions` stays untouched.** Spec §5.4 only mandates the kernel
   options field; step-1.2 precedent (`kernelOptionsPost` untouched). Post
   options keep flowing as raw strings.
2. **Routing failures propagate.** A `KernelOptionsBuilderException` (blank
   `java.hostname`, missing tree/install type, unknown install type) is not
   caught — consistent with the locked step-1.1/1.2 "unknown install type
   throws" stance. Unreachable in practice: only the UI details-edit path sets
   `kernelOptions`, and that path requires a valid profile with a tree; all
   automated callers leave it null (finding 3). Accepted new failure mode for
   degenerate data, mirroring the distro path's hard hostname dependency.
3. **Shared FQDN helper extracted** — no duplicate helpers: the
   `ConfigDefaults`-based canonical-hostname resolution moves into a small
   static helper used by both `BaseTreeEditOperation` (delegating) and
   `CobblerProfileCommand`. No behavior change for the tree path.
4. **Wiring at the convergence point only.** `KickstartDetailsEditAction`
   needs no change — the roadmap's `KickstartDetailsEditAction →
   CobblerProfileCommand#updateCobblerFields` arrow already exists in code,
   and the command layer covers create + edit + any future caller.
5. **`profileOptions` remains empty** (step-1.1 decision 4): this step wires
   the seam so the G6 flip is a one-line builder override, not a call-site
   sweep. Cobbler's `tftpgen` keeps owning the installer-selection option on
   PXE entries.

## 3. Code design

All changes under `java/core/src/main/java/com/redhat/rhn/`.

### 3.1 `KickstartHostHelper` (new, `com.redhat.rhn.manager.kickstart`)

Placed next to `KickstartUrlHelper` — both consumers live in the
`manager.kickstart.*` namespace. One function (revised after the plan
acknowledgment, see §9): a provided server FQDN is canonicalized; null/empty
falls back to the configured Java hostname **unchanged** — canonicalizing it
could resolve to a container-internal name instead of the external FQDN that
has to be used:

```java
public class KickstartHostHelper {
    private KickstartHostHelper() { }

    public static String getServerFqdn(String serverFqdn) {
        if (serverFqdn == null || serverFqdn.isEmpty()) {
            return ConfigDefaults.get().getJavaHostname();
        }
        try {
            return InetAddress.getByName(serverFqdn).getCanonicalHostName();
        }
        catch (UnknownHostException e) {
            return serverFqdn;
        }
    }
}
```

### 3.2 `BaseTreeEditOperation` (refactor, behavior unchanged)

`java/core/src/main/java/com/redhat/rhn/manager/kickstart/tree/BaseTreeEditOperation.java`
loses its inline FQDN resolution entirely — no wrapper method is kept;
`applyDistroKernelOptions()` calls the helper directly (the `InetAddress`/
`UnknownHostException`/`ConfigDefaults` imports are dropped):

```java
protected void applyDistroKernelOptions() {
    KernelOptionsList options = new KernelOptionsList(this.tree.getKernelOptions());
    options.addMissingOptions(KernelOptionsBuilderFactory
            .getBuilder(this.tree.getInstallType(),
                    KickstartHostHelper.getServerFqdn(this.serverName), this.user)
            .distroOptions(this.tree));
    this.tree.setKernelOptions(options.toString());
}
```

One documented delta vs. the pre-refactor code: when the operation's
`serverName` is blank, the configured Java hostname is now used **as-is**
instead of being canonicalized (see §4) — a provided `serverName` is still
canonicalized, so the UI path (`request.getLocalName()`) is unaffected and
the DB-free merge tests (which canonicalize a provided name) pass unchanged.

The step-1.2 tests (`TreeCreateOperationTest`, the DB-free merge test) keep
passing.

### 3.3 `CobblerProfileCommand#updateCobblerFields` (the wiring)

Replace the raw push:

```java
if (kernelOptions != null) {
    KernelOptionsList options = new KernelOptionsList(kernelOptions);
    options.addMissingOptions(KernelOptionsBuilderFactory
            .getBuilder(ksData.getInstallType(), KickstartHostHelper.getServerFqdn(null), user)
            .profileOptions(profile));
    profile.setKernelOptions(Optional.of(options.toString()));
}
```

* `ksData.getInstallType()` — routing key (finding 5); the factory throws on
  null/unknown per decision 2.
* `user` passed through — user-scoped installer-updates lookup once
  `profileOptions` becomes non-empty; null (taskomatic) → org-global, per
  `20-kernel-options.md` §5.5.
* `profileOptions(profile)` receives the cobbler profile being written —
  empty today (decision 5), so the merge is a no-op and the saved string is
  the parsed/normalized user input.
* `postKernelOptions` block stays byte-identical (decision 1).
* New imports: `KernelOptionsList`, `KernelOptionsBuilderFactory`,
  `KickstartHostHelper`.

No schema, UI/JSP, Struts or XML-RPC API changes.

## 4. Behavior deltas (parity register)

| Delta | Reason / notes |
|---|---|
| Whitespace normalization of saved profile kernel options (multiple spaces collapse, leading/trailing trim) | `KernelOptionsList` parse→serialize; same documented normalization class as step 1.2 (spec §5.1.1). |
| `<<inherit>>`, empty string, quoted values round-trip unchanged | Verified against `KernelOptionsList` parsing (quotes stay in tokens) and cobbler `INHERIT_KEY` handling — legacy parity. |
| `profileOptions` merge is a no-op today | Empty until G6 (decision 5); idempotent under repeated store. |
| Routing failure → `KernelOptionsBuilderException` propagates | Decision 2. New failure mode, unreachable for existing `rhnKSInstallType` labels; automated paths never set `kernelOptions`. |
| Blank `serverName` on the tree path → configured Java hostname used as-is (no canonicalization); a provided name is still canonicalized | Helper revision (§9): protective against container-internal resolution — the configured hostname is the external FQDN; legacy canonicalized both cases. |
| `postKernelOptions` still pushed raw | Decision 1. |

## 5. Test plan

### 5.1 DB-backed (`CobblerCommandTest`, runs on the agent VM / CI)

Fixture: `CobblerCommandTestBase` (PostgreSQL + mocked cobbler connection).
Assertions read the stored options back via `Profile.lookupById(...)` +
`convertOptionsMap(prof.getKernelOptions().get())` — the same rendering the
details UI uses (exact mechanics settled while implementing).

* **Normalization** — `setKernelOptions("  opt1=val1   opt2  ")` → store →
  profile options equal `opt1=val1 opt2`.
* **Quoting** — `key="value with spaces"` round-trips with quotes preserved.
* **Legacy parity** — `<<inherit>>` → `<<inherit>>`; `""` → `""`.
* **Idempotency** — store twice (create, then edit round-trip) yields an
  identical options string.
* **Post options untouched** — `post_kernel_options` string stored verbatim
  (incl. unnormalized spacing).

The fixture's tree install type does not change the assertions
(`profileOptions` is empty); routing is exercised implicitly — `store()` must
not throw.

### 5.2 DB-free

None new: `KernelOptionsList` parse/serialize and factory routing are already
covered by the step-1.1 suites. `KickstartHostHelper`'s
`UnknownHostException` fallback needs `rhn.conf`/`ConfigDefaults` on the
classpath — covered by the DB-backed suite only (same note as step-1.2 §5.2).
A tiny pure test for `canonicalize` may be added if it stays trivial;
otherwise skip (no dead weight).

### 5.3 Style gates (local sandbox)

```
mvn -f java compile checkstyle:checkstyle
```

Ant is no longer used for checkstyle (user decision 2026-10-08): the Maven
checkstyle goal replaces the previous `ant manager-build.xml checkstyle` chain
and the step-1.2 CLI audit workaround.

## 6. Verification on the agent VM

Same protocol as step 1.2 §6 — single shared VM, record the usage window and
deployed package versions in `agents-workbench/autoinstallation/session-log.md`.

1. Coordinate the VM window; check `podman ps` state and
   `podman exec uyuni-server rpm -q spacewalk-java` before/after.
2. When the running Uyuni is not needed: `mgradm stop` (frees port 5432).
3. Test DB per `java/Makefile.docker` flow: `uyuni-master-pgsql-4eclipse`
   container with the worktree mounted at `/manager`; verify the branch
   schema is loaded (incl. `opensuse16.1`).
4. Run: `mvn -f java/core test -DskipTests=false
   -Dtest='CobblerCommandTest,KernelOptions*Test,*KernelOptionsBuilder*Test'`
   with `JAVA_HOME` at JDK 17.
5. Afterwards: stop/remove the test container, `mgradm start`, record results
   and versions in the session log.

Full PXE/UI E2E regression stays with step 1.7 (mind the cobbler PR #177
caveat, `00-goals.md` §5.1).

## 7. Documentation updates (implementation session)

* `10-roadmap.md`: step 1.5 → DONE + changelog entry.
* `20-kernel-options.md` §5.4: mark the profile path wired; note the
  normalizations, the propagate-on-routing-failure semantics and the
  post-options decision.
* Regenerate the presentation HTMLs: `.venv/bin/python autoinst_plan/build_html.py`.
* Session log entry in `agents-workbench/autoinstallation/session-log.md`.

## 8. Out of scope

* System path (`CobblerSystemCreateCommand#processKernelOptions`) — step 1.3.
* Initiate path (`KickstartInitiateAction#buildKernelOptions`) — step 1.4.
* `KickstartCloneCommand` option copying (inventory item 12) — untouched.
* `CobblerDistroSyncCommand#backsyncKernelOptions` reverse sync (item 9;
  Phase 1 deferral stands).
* Wizard/SSM option resolution — dies with the wizard (step 2.6).
* Enabling `profileOptions` — G6 flip-switch (decision 5).
* Any UI/JSP or API change.

## 9. Implementation notes (2026-10-08)

* Implementation followed §3: `KickstartHostHelper` added under
  `com.redhat.rhn.manager.kickstart`, `BaseTreeEditOperation#getServerFqdn`
  delegates to it, `CobblerProfileCommand#updateCobblerFields` routes the
  user string through `KernelOptionsList` + the factory-routed (empty)
  `profileOptions` merge.
* **Helper revision after the plan acknowledgment:** the initial two-function
  design (`canonicalize` + `localServerFqdn`) was replaced by a single
  `getServerFqdn(String serverFqdn)` — a provided FQDN is canonicalized,
  null/empty falls back to the configured Java hostname unchanged. Rationale:
  "local" naming and canonicalizing the configured value could resolve to a
  container-internal name instead of the external FQDN that has to be used.
  Parity delta registered in §4. On review, the temporary
  `BaseTreeEditOperation#getServerFqdn()` wrapper was dropped —
  `applyDistroKernelOptions()` calls the helper directly.
* Test mechanics: the cobbler mock (`MockConnection`) stores string setter
  values raw and interprets the literal `<<inherit>>` as inherit on read-back
  and `""` as an empty dict — mirroring cobbler itself, so the parity
  assertions of §5.1 are meaningful. String round-trips are asserted via
  `assertEquals(expected, prof.getKernelOptions().get())` (the established
  pattern, cf. `SystemHandlerProvisioningTest`). A dedicated
  null-options test (`testProfileEditWithoutKernelOptions`) guards the
  `if (kernelOptions != null)` skip used by all automated callers.
* The fixture tree (`CobblerCommandTestBase` → `KickstartableTreeTest`) carries
  a real `rhnKSInstallType` row, so the factory routing in
  `updateCobblerFields` never sees an unknown type — `ksData.getInstallType()`
  is always non-null on the paths that set kernel options.
* Checkstyle: `mvn -f java compile checkstyle:checkstyle` clean (ant
  toolchain dropped per user decision).
* Verification: local — 44 DB-free tests green (builder/factory/list +
  step-1.2 merge) after the helper revision, checkstyle clean. Agent VM
  (two windows: initial implementation ~20:30–20:40 CEST, helper revision
  ~22:55–23:05 CEST): `mgradm stop` → dedicated `uyuni-test-pgsql` container
  (worktree mounted at `/manager`, branch schema with `opensuse16.1` loaded)
  → 67 tests green each time (`CobblerCommandTest` 16 incl. the 7 new
  profile-options cases, 41 builder/factory/list, 10 tree-op) → container
  removed, `mgradm start`, deployed stack healthy
  (`spacewalk-java-5.3.1-260400.1.2.uyuni6.noarch`, unchanged). E2E PXE/UI
  matrix stays with step 1.7.

## Changelog

* 2026-10-08 — created from planning round 4: wiring at the
  `updateCobblerFields` convergence point; `postKernelOptions` untouched;
  routing failures propagate; shared `KickstartHostHelper` extracted;
  DB-backed test plan on the mocked-cobbler fixture.
* 2026-10-08 — plan acknowledged with amendments: checkstyle runs via
  `mvn -f java compile checkstyle:checkstyle` (ant toolchain dropped).
* 2026-10-08 — implemented and verified: 67 tests green on the agent VM,
  checkstyle clean. Status → DONE.
* 2026-10-08 — helper revised per review: single
  `KickstartHostHelper.getServerFqdn(String)` (provided → canonicalize,
  null/empty → configured Java hostname unchanged); parity delta for the
  blank-`serverName` tree path registered; re-verified (44 DB-free local,
  67 DB-backed on the agent VM).
* 2026-10-08 — indirection removed on review: no
  `BaseTreeEditOperation#getServerFqdn()` wrapper; `applyDistroKernelOptions()`
  calls the helper directly. Behavior identical; compile + checkstyle + 44
  DB-free tests green locally (DB-backed coverage unchanged from the
  same-day VM windows).
