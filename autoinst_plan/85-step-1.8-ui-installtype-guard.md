# Step 1.8 Addendum — Web UI install-type guard (distro create/edit NPE fix)

> Post-Phase-1 addendum, opened by a live-test finding: creating a
> distribution through the web UI NPEd when the posted `installtype` did not
> resolve. This doc records the root cause, the minimal fix, and the web-UI
> E2E verification that closes the step-1.7 gap (its matrix was XML-RPC
> only).

| | |
|---|---|
| Branch | `sles16_agama` |
| Created | 2026-10-15 |
| Status | DONE (2026-10-15) |
| Related | `10-roadmap.md` step 1.2 / 1.7, `40-step-1.2-distro-path.md`, `80-step-1.7-cleanup-verification.md`, `AGENTS_CUSTOM.md` §3 |

---

## 1. Finding (user report, 2026-10-15)

Creating a distro via the web UI crashed with:

```
java.lang.NullPointerException: Cannot invoke
"com.redhat.rhn.domain.kickstart.KickstartInstallType.isSUSE()" because
"this.installType" is null
    at KickstartableTree.getDefaultInitrdPaths(KickstartableTree.java:394)
    at KickstartableTree.getInitrdPath(KickstartableTree.java:439)
    at BaseTreeEditOperation.validateBasePath(BaseTreeEditOperation.java:168)
    at BaseTreeEditOperation.store(BaseTreeEditOperation.java:81)
    at TreeCreateOperation.store(TreeCreateOperation.java:63)
    at BaseEditAction.execute(BaseEditAction.java:65)
```

## 2. Root cause

1. `BaseTreeAction#processCommandSetters` assigned
   `KickstartFactory.lookupKickstartInstallTypeByLabel(form value)` to the
   tree **without a null check**. A posted `installtype` label that is not in
   `rhnKsInstallType` (unknown label, stale page, empty install-type list —
   e.g. branch code deployed before the branch DB deltas) silently produced a
   tree without an install type.
2. `BaseTreeEditOperation#validateBasePath` → `KickstartableTree
   .getInitrdPath()` → `getDefaultInitrdPaths()` dereferences
   `installType.isSUSE()` (same landmine in `getDefaultKernelPaths` and the
   two xen getters). Even without the NPE, `applyDistroKernelOptions()`
   (step 1.2) would throw `KernelOptionsBuilderException` one line later —
   the factory routes unknown/null install types.
3. Pre-existing legacy behavior: phase 1 never touched `KickstartableTree`,
   `BaseTreeAction` or `BaseEditAction` (verified via git log — only the
   maven/jakarta/infrastructure commits touch them). The XML-RPC path was
   already guarded (`KickstartTreeHandler#getInstallType` throws
   `NoSuchKickstartInstallTypeException`); only the Struts path was not.

Trace-forensics note: the stack trace line numbers (`TreeCreateOperation
.java:63` = branch's `super.store()`; stock 5.3.1 has it at line 70, verified
via `javap` line tables) proved the report came from a branch deployment —
on the VM the running jar was stock after the step-1.7 rollback while the DB
retained `opensuse16.1` (id 13).

## 3. Fix (minimal, action-level only — user-locked scope)

`BaseTreeAction#processCommandSetters`: resolve the label defensively and
return a `ValidatorError` instead of setting a null install type (covers
create **and** edit; mirrors the XML-RPC guard):

* null/unknown label → `new ValidatorError("kickstart.tree.invalidinstalltype",
  installTypeLabel)` — the UI shows the offending label.
* The null-label check sits before the lookup because
  `HibernateFactory#lookupObjectByParam` throws a raw NPE on a null parameter
  (`Objects.requireNonNull`).
* New message key `kickstart.tree.invalidinstalltype`
  ("The installer type is not valid: {0}") in `StringResource_en_US.xml` and
  `StringResource_de.xml` (repo convention; other locales follow l10n sync).
* No domain hardening (deliberate — user decision): a null install type on a
  tree remains a programming error; the action guard prevents the only
  realistic source.

## 4. Tests

* New DB-backed `BaseTreeActionTest`
  (`java/core/src/test/java/com/redhat/rhn/frontend/action/kickstart/tree/`,
  `JMockBaseTestCaseWithUser` + `ByteBuddyClassImposteriser` for
  `DynaActionForm`/`HttpServletRequest` — first Struts-action-level test in
  the tree, following the step-1.1 jMock pattern):
  * unknown label → `ValidatorError` key `kickstart.tree.invalidinstalltype`,
    install type stays null, no NPE;
  * missing (null) form value → same rejection;
  * valid label (`opensuse16.1`) → type set, label/basepath applied, store
    values untouched.
* VM run (pgsql-4eclipse container, branch schema): 16 tests green —
  3 `BaseTreeActionTest` + 8 `TreeCreateOperationTest` + 5
  `TreeEditOperationTest`.
* Local sandbox: `mvn -f java/core compile` / `test-compile` /
  `checkstyle:checkstyle` clean.

## 5. Web-UI E2E (closes the step-1.7 UI gap)

Executed 2026-10-15 on the agent VM against the branch-deployed backend
(deploy per `AGENTS_CUSTOM.md` §3; deployed-code fingerprint verified:
`applyDistroKernelOptions` in the jar's `BaseTreeEditOperation` constant
pool + `TreeCreateOperation.store` line-63 signature). Reused the step-1.7
`e2e17-*` channels + dummy kernel/initrd layouts under `/srv/www/`:

| Row | Result |
|---|---|
| Create page renders; dropdown lists `opensuse16.1` | OK |
| Create with `installtype=bogus-install-type` | HTTP 200 + "The installer type is not valid: bogus-install-type", no NPE (page and `rhn_web_ui.log`) |
| Create `ui18-sles15` (sles15generic) | success; DB + cobbler kopts `install=http://…/ks/dist/ui18-sles15 self_update=0` |
| Create `ui18-rhel7` (rhel_7) | success; `inst.repo=http://…/ks/dist/ui18-rhel7` |
| Create `ui18-sle16` (opensuse16.1) | cobbler PR #177 rejection (`os_version … given was "opensuse16.1"`) — the documented caveat; DB row rolled back; the cobbler distro stub left by the failure path was removed manually |
| Edit `ui18-sles15` install type → rhel_7 | add-missing recompute: stale `install=`/`self_update=0` kept + `inst.repo=` added (step-1.2 §4 delta), cobbler distro updated in sync |
| Edit with `installtype=bogus-edit-type` | HTTP 200 + validation error; tree unchanged |
| Revert edit → sles15generic | options keep the stale `inst.repo=` (add-missing-only, documented) |

The `localhost` in option URLs is a curl artifact (`request.getLocalName()`
from the AJP request); a browser session produces the accessed hostname.

## 6. Documentation/deployment notes

* `AGENTS_CUSTOM.md` §3: deployed-code fingerprint check added to the deploy
  recipe (rpm NVR is blind to overlay deploys — check the jar itself).
* VM clock skew note: the sandbox runs ~2 h ahead of the VM; deploy tar
  warnings "time stamp … in the future" are benign for this flow.
* Remnants left on the VM (recorded in the session log): `ui18-sles15` /
  `ui18-rhel7` trees + channels + cobbler distros (mirroring the `e2e17-*`
  remnants), branch backend deployed (rhn.jar overlay; a container
  recreation reverts to stock per §3).

## Changelog

* 2026-10-15 — created: root cause, fix, tests, UI E2E matrix, deploy
  fingerprint note. Status → DONE.
