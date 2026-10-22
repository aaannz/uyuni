# Autoinstallation Overhaul — Implementation Roadmap

> Working breakdown of the goals in [`00-goals.md`](00-goals.md) into
> implementable steps. Each session: pick one step, write its plan detail
> (inline section or a linked step doc), implement, verify, update status.
> Guardrails and locked decisions in `00-goals.md` §4/§5 always apply.

| | |
|---|---|
| Branch | `sles16_agama` |
| Created | 2026-10-07 |
| Status legend | `NOT STARTED` / `IN PROGRESS` / `DONE` / `DEFERRED` (with reason) |

---

## Phase 1 — Unified kernel options (G0)

Builder hierarchy, factory routing, and the catalogue of legacy strings to
reproduce live in [`20-kernel-options.md`](20-kernel-options.md) — read it
before starting any step here.

### Step 1.1 — Port builder hierarchy (status: DONE 2026-10-08)

* Implemented per `20-kernel-options.md` §2–§4 and the step design in
  [`30-step-1.1-builders.md`](30-step-1.1-builders.md): interface, abstract
  base (configurable `serverFqdn`, optional `User` context), factory, builders
  `Rhel` / `AutoYast` / `Agama` under
  `com.suse.manager.autoinstallation.{builder,installer}`; no Debian builder
  (no debian/ubuntu install type exists — unroutable dead code, decision
  recorded in the step design §1.3).
* SLE16 baseline (`00-goals.md` §5.1): `opensuse16.1` install type, cobbler
  breed `suse`, `http://` URLs; factory routes `isSLES16OrGreater()` → Agama
  **before** the SUSE branch; routing is install-type-only — unknown types
  throw `KernelOptionsBuilderException`, no breed-based fallback.
* `distroOptions` idempotent (`setOptionIfNotPresent`) — results are persisted
  and recomputed on tree create/edit.
* `profileOptions` stays in the contract but is empty until the Cobbler exit
  (G6): cobbler's tftpgen owns the installer-selection option on PXE entries
  (step design §1.4).
* 34 DB-free unit tests; compile + checkstyle clean.

### Step 1.2 — Distro path (status: DONE 2026-10-08)

* Implemented per [`40-step-1.2-distro-path.md`](40-step-1.2-distro-path.md):
  `BaseTreeEditOperation#applyDistroKernelOptions()` merges
  `KernelOptionsBuilder.distroOptions` into the tree's options (user-typed
  options win) and runs on **create and edit** via `BaseTreeEditOperation#store`
  — fixing the XML-RPC `kickstart.tree.update` option-wipe; blank `serverName`
  falls back to `ConfigDefaults.getJavaHostname()`.
  `TreeCreateOperation` keeps only the duplicate-label check.
* Preserve: persistence into `KickstartableTree.kernelOptions(_post)`
  (`kernelOptionsPost` untouched); user-scoped installer-updates channel
  lookup (§5.5); the `useonlinerepo` flag survives round-trips (decision
  `00-goals.md` §5.8).
* On the way: test fixture gap fixed (`TreeOperationTestBase` now also
  creates the SUSE loader kernel/initrd layout — SUSE-type `store()` used to
  fail validation). (The originally recorded "RHEL `systemOptions` `inst.ks=`
  → `ks=` repair" did not stick: `inst.ks=` is the committed, user-locked
  form — RHEL 7+ consumes `inst.ks`; the spec wording was stale and is
  corrected in the step-1.3/1.4 round, see
  `60-step-1.3-1.4-system-and-initiate-path.md` §1.8.)
* Verified on the agent VM (dedicated pgsql-4eclipse container after
  `mgradm stop`; branch schema incl. `opensuse16.1`): 57 tests green,
  full-core checkstyle audit clean. PXE E2E matrix stays with step 1.7.

### Step 1.3 — System path (status: DONE 2026-10-09)

* Implemented per `20-kernel-options.md` §5.2 and the design in
  [`60-step-1.3-1.4-system-and-initiate-path.md`](60-step-1.3-1.4-system-and-initiate-path.md):
  `CobblerSystemCreateCommand#processKernelOptions` is generic (no breed
  gate, install-type routing via the factory) — session-tracked media
  override (`mediaOptions`: `install=` / `inst.repo=` / `inst.install_url=`)
  plus `self_update` re-evaluated at schedule time with the schedule user
  (custom installer-updates children are org/user-tied); resolved opt-out
  honored in both `self_update=0` and `inst.self_update=0` forms;
  `kernelOptionsPost` still raw. SLE16 records now get the Agama form
  `inst.self_update=` (bug fix); RHEL-family records get `inst.repo=` (new
  session tracking for every OS, locked user decision).
* `KickstartScheduleCommand#getExtraOptions` continues to produce the user
  part (`ksdevice=` + custom options) for now (full resolution in step 2.6).
* On the way: `MockConnection#get_item_resolved_value` repaired (it could
  never match — resolved getters always returned null under the mock; now a
  proper inheritance merge with a cycle guard — the saltboot fixtures name a
  profile like its distro); `KernelOptionsList#getValue` added (was in the
  spec's starting-point description but never implemented);
  `SystemHandlerProvisioningTest` expectation updated to the intended
  `inst.repo=` override.

### Step 1.4 — Initiate path (status: DONE 2026-10-09)

* Implemented per `20-kernel-options.md` §5.3 and the step design:
  `KickstartInitiateAction#buildKernelOptions` composes
  `KernelOptionsList(resolved cobbler options)` → `KernelOptionsHelper
  .applySuseTextmode` (shared SUSE helper, first caller, breed-gated) →
  `removeOption(initrd)` → factory-routed `systemOptions` (`autoyast=` /
  `inst.auto=` / `inst.ks.sendmac` + `inst.ks=`) → `info=` via
  `cobblerSystemNopxeUrl`; the hand-rolled map serializer is gone; the
  debian/ubuntu and rhel6 branches are dropped (locked); routing by install
  type (tree resolved from the cobbler distro name in `prepareCobblerBoot`).
* The salt state (`bootloader.autoinstall`) kopts now include the resolved
  system options — among them the session-tracked media URL — plus the
  builder's system options, per the planning-round-5 requirement.
* Design + decisions (planning round 5, combined with 1.3):
  [`60-step-1.3-1.4-system-and-initiate-path.md`](60-step-1.3-1.4-system-and-initiate-path.md).
* Verified together with 1.3: 126 + 109 tests green on the agent VM
  (`mgradm stop` → pgsql-4eclipse container via `:Z` mount — SELinux
  enforcing; branch schema incl. `opensuse16.1`), `mvn -f java compile
  checkstyle:checkstyle` clean (no ant). PXE E2E matrix stays with 1.7.

### Step 1.5 — Profile path (status: DONE 2026-10-08)

* Implemented per [`50-step-1.5-profile-path.md`](50-step-1.5-profile-path.md):
  wire `profileOptions` handling into profile create/edit at the
  `CobblerProfileCommand#updateCobblerFields` convergence point
  (spec: `20-kernel-options.md` §5.4). Kernel options edited in the profile
  details UI flow through `KernelOptionsList` instead of raw strings;
  `KickstartDetailsEditAction` itself needs no change.
* Narrowed by the step 1.1 decision: no `inst.auto`/`inst.auto_insecure`
  merge — `profileOptions` is empty until the Cobbler exit (G6), cobbler owns
  the installer-selection option on PXE entries
  (`30-step-1.1-builders.md` §1.4).
* Decisions (planning round 4): `postKernelOptions` stays raw;
  routing failures (`KernelOptionsBuilderException`) propagate; the step-1.2
  FQDN resolution moved into a shared `KickstartHostHelper` (used by
  `BaseTreeEditOperation` + the profile path).
* Verified: 67 tests green on the agent VM (16 `CobblerCommandTest` incl. 7
  new profile-options cases: normalization, quoting, `<<inherit>>`/empty
  round-trip, idempotency, post-options raw, null-options skip; 41
  builder/factory/list; 10 tree-op after the FQDN-helper refactor),
  `mvn -f java compile checkstyle:checkstyle` clean (ant toolchain dropped).

### Step 1.6 — Tests (status: DONE 2026-10-09)

* Per `20-kernel-options.md` §7: pure-logic builder/factory tests, entity
  tests for install-type routing, call-site parity tests (legacy string
  equality modulo documented whitespace normalization), idempotency tests.
  DB/Cobbler-free where possible (CI runs the rest).
* Design in [`70-step-1.6-tests.md`](70-step-1.6-tests.md): remaining §7
  gaps closed (abstract-builder org-global branch, `KickstartHostHelper`,
  predicate truth table + breed/os-version identity, RHEL merge +
  Agama mixed-children, `KernelOptionsList` API holes, exact-string SLE16/
  RHEL system parity, schedule-user negative scoping, tree-edit recompute,
  initiate idempotency + RHEL text survival).
* Opt-in **real-cobbler test mode** (`-Duse.real.cobbler=true` +
  availability assumption): the phase-1 DB-backed suites run against the
  cobbler shipped in the pgsql-4eclipse test container (accept-all auth
  shim for the `authentication.spacewalk` callback protocol + container
  setup script under `java/buildconf/test/cobbler/`), with mandatory
  per-test cobbler cleanup. Mock-vs-real audit findings in the step doc
  §8.1; the five SLE16 fixtures skip against a cobbler without the PR #177
  signature (documented caveat). Verified: 161 mock + 50/55 real-mode
  tests green on the agent VM, checkstyle clean.

### Step 1.7 — Cleanup & verification (status: DONE 2026-10-12)

* Remove dead string-building code after all sites migrated; checkstyle; E2E
  regression matrix per `20-kernel-options.md` §8 on the agent VM (mind the
  cobbler PR #177 caveat for SLE16 PXE).
* Implemented per
  [`80-step-1.7-cleanup-verification.md`](80-step-1.7-cleanup-verification.md)
  (scope A+B+C: 7 verified-dead members removed incl. the test-only ones,
  `getValue` stays). The legacy string-building code itself was already
  deleted during steps 1.2–1.4.
* Verified on the agent VM: checkstyle clean; 180 mock-mode tests + 50/55
  real-mode tests (SLE16 skips, PR #177) green. E2E matrix executed with
  fake content through the real flow: SLES15 tree/profile/schedule +
  cobbler records (`install=`/`self_update=0`, media tinyurl override on the
  system record), RHEL7 (`inst.repo=`), salt initiate pillar showing the
  full composed boot line (`autoyast=` + media override + `info=`), SLE16
  documented PR #177 caveat (cobbler rejection recorded). First backend
  deploy of the branch on the VM (deploy quirks recorded in
  `AGENTS_CUSTOM.md` §3); stack restored.

### Phase 1 explicit deferrals (documented per guardrail)

* `ScheduleKickstartWizardAction#parseKernelOptions` / `SSMScheduleCommand`
  option resolution — wizard UI dies in Phase 2 (step 2.6).
* Saltboot PXE option maps (`SaltbootUtils`, `PXEEventMessageAction`) —
  Phase 3, step 3.4.
* `CobblerDistroSyncCommand#backsyncKernelOptions` reverse-sync — keep
  behavior; revisit under the G6 seam (step 5.1).

---

## Phase 2 — Kickstart modernization + React UI (G1 + G2)

> Deferred by user decision 2026-10-19: G3 backend proceeds first
> (`00-goals.md` §5.7 amendment). Step statuses unchanged; step 2.2 remains
> the prerequisite for the saltboot UI (step 3.3).

### Step 2.0 — Feature tiering decisions (status: NOT STARTED)

* Research + decide feature-by-feature (rule: `00-goals.md` §5.3) for the
  register in §5 below; record outcomes as new locked decisions in
  `00-goals.md` §5.3. Output of this step unblocks 2.1–2.5 scope.

### Step 2.1 — Unified profile entity design (status: NOT STARTED)

* Design (schema + Java domain + API surface) of the type-based
  autoinstallation profile entity (`00-goals.md` G4): types kickstart /
  autoyast / agama / (reserved: cloud-init, ignition, combustion), serving URL
  scheme, org scoping, migration path from the legacy kickstart data model
  (additive, no cutover in this phase).
* Constraint (decision `00-goals.md` §5.8): raw profile content stays on disk
  until G6 — the entity design must accommodate that without rework.

### Step 2.2 — React profile pages (status: NOT STARTED)

* Profile list, create (channel/distro/virt + file upload or paste),
  details/edit (raw content + download), delete/clone. Spark controllers +
  jade + `web/html/src/manager/autoinstallation/` following the images
  feature pattern; metadata-driven fields per profile type (guardrail 3).

### Step 2.3 — React distributions pages (status: NOT STARTED)

* KickstartableTree list/create/edit/delete + variables; kernel options field
  uses G0 builder output for defaults; keep XML-RPC `KickstartTreeHandler`
  compatible.

### Step 2.4 — Wizard removal (status: NOT STARTED)

* Remove Struts wizard routes/actions/JSPs (creation wizard, wizard-mode
  editing tabs, schedule wizard), nav XMLs, the wizard-mode template
  generation path (`KickstartFormatter` + file-sync task) and the wizard
  branch of the profile builder.
* Careful sequencing: registration still depends on Cobbler snippets
  (`redhat_register_using_salt` et al.) — those keep working for raw profiles
  until G6 (decision `00-goals.md` §5.8); raw scheduling flow
  (`KickstartScheduleCommand`, `KickstartInitiateAction`) must keep working
  after wizard removal.
* Update API docs/deprecation notes for removed wizard-only API methods.

### Step 2.5 — Support pages per tier decisions (status: NOT STARTED)

* Migrate (or drop, per step 2.0 outcomes): snippets, crypto keys, file
  preservation, IP ranges, sessions, scripts/variables if kept.

### Step 2.6 — Resolve deferred kernel-option consumers (status: NOT STARTED)

* With the schedule wizard gone: settle `KickstartScheduleCommand`
  user-option handling (`getExtraOptions`) and SSM scheduling path fully onto
  builders; close Phase 1 deferrals that Phase 2 unblocks.

---

## Phase 3 — Saltboot integration (G3)

### Step 3.1 — Saltboot deployment model design (status: DONE 2026-10-19)

* Design in [`90-step-3.1-saltboot-model.md`](90-step-3.1-saltboot-model.md);
  decisions promoted to `00-goals.md` §5.9.
* Two entity families: `suseSaltbootBranch` (branch settings per branch group,
  proxy FK resolved to the primary FQDN, naming, default boot image/kernel
  parameters; replaces `saltboot-group`) and hierarchical `suseSaltbootConfig`
  (JSONB partitioning with parent chain, assigned to groups/systems; replaces
  `saltboot`), plus `suseSaltbootTerminal` (per-terminal state + force flags).
* Effective config = deep merge by disk/partition IDs along the chain (JSON
  `null` tombstones); resolution system > most-derived > priority >
  notification (A/B rollouts that only switch the image).
* Transition: idempotent upgrade-time migration (`UpgradeCommand`) + dual-write
  from the formula-save hooks; users unassign formulas when ready.
  Containerized proxies only; boot image refs are lax strings.
* Verification correction: today's formula merge replaces `edit-group` values
  and keeps only one group's data per formula (step doc §1.2) — migration maps
  per-system partitioning to root configs.

### Step 3.2 — Java domain, schema, API (status: DONE 2026-10-19, E2E partial)

* Implement the model + XML-RPC/REST API; keep `SaltbootUtils` Cobbler entry
  generation working, routed through the new domain.
* Design: `90-step-3.1-saltboot-model.md` §2–§5 — entities, resolver/merge,
  pillar generator, `saltboot` XML-RPC namespace, upgrade migration
  (`UpgradeCommand` task + idempotent `upgrade/next/` queue script), and
  dual-write in `FormulaFactory` (formula unassignment must no longer delete
  the cobbler group profile when a branch exists). Hand-off list in §6.1.
* Implemented per [`95-step-3.2-saltboot-implementation.md`](95-step-3.2-saltboot-implementation.md):
  4 tables + idempotent `upgrade/next` scripts (model, migration task, 18 API
  RBAC rows), entities/factory/DB-free resolver, `SaltbootPillarGenerator` in
  the full `MinionPillarManager` path, `SaltbootManager` + `saltboot` XML-RPC
  namespace, formula dual-write, `UpgradeCommand` migration, `SaltbootUtils`
  reading the domain (formula first), §1.9 fix. Extra config columns
  `terminal_kernel_parameters`/`freeze_image` (found in the formula form).
* Verified on the agent VM: 371 tests green (155 saltboot/pillar + 216
  neighbouring), checkstyle clean (main + tests); real XML-RPC run: 11 checks
  green, then blocked by duplicate default access groups on the VM (see step
  doc §5) — rest of the E2E script pending.

### Step 3.3 — React UI (status: NOT STARTED — blocked on step 2.2)

* Saltboot area inside the new autoinstallation UI: deployment profiles,
  group/terminal targeting, image selection, status; formula form replaced.

### Step 3.4 — PXE/kernel options via builders (status: IN PROGRESS — split 2026-10-19)

Split into sub-steps across sessions (planning round 9). The pending E2E
runs of 3.4a-2 and 3.4b use the VM workflow in
[`05-agent-vms.md`](05-agent-vms.md) (agentvm2: unit tests; agentvm: branch
deploy + E2E).

#### Step 3.4a — Saltboot distro as image-based tree (status: IN PROGRESS — 3.4a-1 DONE 2026-10-20, 3.4a-2 implemented 2026-10-20, E2E pending)

* Design: [`96-step-3.4a-saltboot-image-tree.md`](96-step-3.4a-saltboot-image-tree.md)
  — `KickstartableTree` with nullable `channel_id` + new `image_id`
  (exactly one set), `saltboot` install type (cobbler breed `generic`),
  `base_path` = image dir, kernel/initrd from `ImageFile`; kickstart-facing
  factory lookups exclude image trees (one shared label space); a generated
  tree (`name-version-revision`) per image plus user-created image trees via
  new `kickstart.tree` XML-RPC calls taking an image id (existing calls
  reject `saltboot`, JSP UI hides it); deleted with the image; upgrade task
  for existing images. Java-side distro record for the Cobbler exit (G6).
  Decisions T1–T7 locked; labels stay ≤ 64 chars, image-tree labels allow
  dots (O1/O2). Session 3.4a-1 planned in the step doc §5a.
* Sessions: 3.4a-1 (schema, install type, entity, lookup filters, guards,
  tests), 3.4a-2 (lifecycle, migration task, image-tree API, VM E2E).
* 3.4a-1 implemented per the step doc §5a: schema + idempotent `030-` upgrade
  script, `saltboot` install type (breed `generic`), `KickstartableTree.image`,
  image-based kernel/initrd paths, `image IS NULL` filters on the kickstart
  lookups, install-type list filter, unfiltered `treeLabelExists`, image-tree
  lookups, T7 guards (UI action + XML-RPC). Verified on the agent VM: 65 + 179
  tests green incl. 10 new `KickstartImageTreeTest`, upgrade script applied
  twice and replayed on a rebuilt pre-change schema, checkstyle clean for the
  changed files.
* 3.4a-2 planned in the step doc §5b (planning round 10): generated tree
  from the inspect hook + a delayed upgrade task, **T6 revised** — image
  trees own their cobbler distro (saltboot `:S:` naming, existing distros
  adopted), `createSaltbootDistro` reduced to the profiles (+ legacy fallback),
  `ImageTreeManager`, five new `kickstart.tree` image-tree calls
  (`config_admin` RBAC), E2E with seeded fixtures (VM has no OS images).
  O3 decided (b): only the own generated label is reserved, the
  generated-shaped-label risk is documented.
* 3.4a-2 implemented per the step doc §5b (code + unit tests; the VM E2E of §5b.5
  is still pending): `ImageTreeManager` (generated tree ensured by the inspect
  hook and the delayed `upgrade_satellite_create_saltboot_trees` task, distro
  adopt-or-create, user-tree create/update/delete, trees removed with the image),
  image-aware `CobblerDistroHelper`/`getCobblerDistroName`, `createSaltbootDistro`
  reduced to the profiles (legacy distro only as fallback), five `kickstart.tree`
  image-tree calls + RBAC rows (`050-`), queue script `040-`. Verified on the agent
  VM: 114 + 24 tests green in the affected suites, checkstyle (main + tests) clean.

#### Step 3.4b — Saltboot builder (status: IMPLEMENTED 2026-10-21 — tests green on the VM; E2E pending)

* Plan: [`97-step-3.4b-saltboot-kernel-options.md`](97-step-3.4b-saltboot-kernel-options.md)
  — `SaltbootKernelOptionsBuilder` joins the `KernelOptionsBuilder` interface,
  routed by the `saltboot` install type (3.4a), `distroOptions(imageTree)`;
  branch/terminal bound at construction (proxy FQDN + proxy creator as
  user); `pxe_update` persists into `SaltbootTerminal` first; system records
  recompute branch options (no cobbler read-back); cobbler transport stays
  a `Map` (decisions K1–K5).
* Implemented per the step doc (session 2026-10-21): `SaltbootKernelOptionsBuilder`
  (factory routes `isSaltboot()` first; 2-arg constructor = distro-only builder,
  4-arg = bound branch/terminal), `KernelOptionsList#toMap()`, `SaltbootUtils`
  (`branchBuilder`, group profile and system record via the builder,
  `createSaltbootSystem(minion, terminal, group, hwAddresses)`, parser and
  `DISTRO_KERNEL_OPTIONS` removed), `SaltbootFormulaMapper#fillBranch`,
  `PXEEventMessageAction`/`ProxyBackupEventAction` persist the `SaltbootTerminal`
  first (`last_result` `OK`/failure message), generated image tree gets its
  options from the builder. New `SaltbootKernelOptionsBuilderTest` (DB-free) plus
  updated `SaltbootUtilsTest`/`SaltbootMigrationUtilsTest`/`PXEEventTest`.
* Verified on agentvm2 with Maven (VM proxy config fixed; obs-to-maven could not
  fetch the Uyuni repo there — its redirect hits a blocked mirror — so the
  `repository/uyuni` jars were produced locally and rsynced; two servlet-API
  `@Override`s in unrelated test helpers dropped in the VM copy only): main +
  test tree compile, 612 tests green in the saltboot/autoinstallation/kickstart/
  tree/proxy/pillar suites against the pgsql-4eclipse container (mock cobbler),
  checkstyle clean on all changed files (CLI 10.12.7). One test expectation
  changed: `testDomainBranchWithoutImageUsesTheDefaultImage` now expects the bare
  `MASTER` flag for a proxy-less branch (design §3.4). **Not run:** real-cobbler
  mode and the E2E of the step doc §5.

Original step scope:

* Route `SaltbootUtils`/`PXEEventMessageAction` option construction
  (`panic=60 splash=silent`, per-terminal params, master/minion prefixes)
  through the G0 builder hierarchy (new saltboot builder or extension),
  closing the Phase 1 deferral. Saltboot's `generic`-breed "own the whole
  command line" pattern is the reference for post-Cobbler option ownership
  (`20-kernel-options.md` §6).
* Option sources move to domain fields (branch, terminal state) — see
  `90-step-3.1-saltboot-model.md` §6.2.

### Step 3.5 — Trigger/orchestration model (status: NOT STARTED)

* Deployment triggering via the autoinstallation action/schedule flow instead
  of formula-save hooks; preserve the proven `suse/manager/pxe_update` event
  loop (`PXEEventMessageAction`) and force-redeploy/repartition flag semantics.
* Formula-save hooks reduce to dual-write (removed once the formulas are
  retired); the legacy branch-server reactor path is not preserved
  (containerized proxies only, `00-goals.md` §5.9).

---

## Phase 4 — Generic mechanisms + image integration (G4 + G5)

### Step 4.1 — Profile types for cloud-init / ignition / combustion (status: NOT STARTED)

* Implement the reserved types on the unified entity (step 2.1): upload/
  storage/download/serving; validation per type (e.g. ignition JSON schema);
  type metadata for the UI.

### Step 4.2 — Serving + boot integration (status: NOT STARTED)

* Serving endpoints following `/ks/cfg`-style conventions; builders for each
  mechanism's boot parameters (e.g. ignition config URL, combustion delivery)
  — no new call-site string logic (guardrail 1).

### Step 4.3 — Image-build auto-registration (status: NOT STARTED)

* Hook the build-result pipeline (`ImageBuildAction` → `ImageInfo`/`ImageFile`
  → OS-image pillar): supported artifacts (Kiwi OS image with saltboot
  payload, EIB `combustion.iso` artifacts, later ignition-carrying images)
  auto-register as autoinstallation options appearing in the Phase 2/3 UI.

### Step 4.4 — Builders for new mechanisms (status: NOT STARTED)

* Extend the G0 hierarchy with mechanism-specific option sets; keep
  per-OS-class × mechanism composition explicit and testable.

---

## Phase 5 — Cobbler exit preparation, design only (G6)

### Step 5.1 — InstallServer/BootServer seam (status: NOT STARTED)

* Define the abstraction interface over `org.cobbler` + cobbler command
  classes (profile/distro/system CRUD, sync, boot-entry generation, nopxe
  signaling) and migrate Java callers to it. Behavior unchanged; Cobbler stays
  the only implementation.

### Step 5.2 — Cobbler-exit design document (status: NOT STARTED)

* Design (not implement): Java-side grub entry generation into tftpboot,
  profile serving without Cobbler/Cheetah (raw-only makes this feasible),
  kernel/initrd/grub artifact lifecycle, re-pointing of the 5-min sync task,
  grub-only (pxelinux dropped) with UKI extensibility, and the replacement
  activation-key delivery mechanism (G6 discovery item, `00-goals.md` §5.8).

### Step 5.3 — Proxy compatibility design (status: NOT STARTED)

* Analyze `containers/proxy-tftpd-image/tftp_wrapper.py` (HTTP-fetched PXE
  entries, saltboot filtering by `MASTER=`, FQDN rewriting) against the
  Java-generated entry format. Contract (`00-goals.md` §5.8): an older proxy
  container must keep working against a newer server — adjustments to the
  wrapper are allowed, breaking changes to served formats are not.

---

## Dependency map

```
Phase 1 (kernel options) ──► Phase 2 (raw-only + UI)
      │                            │
      ├──► Phase 3 backend         └──► 3.3 saltboot UI (needs 2.2)
      │    (3.1/3.2/3.4/3.5) ─────────► Phase 4
      │
      └── builders reused in 3.4/4.2/4.4
                                   Phase 2.1 (unified entity) ──► Phase 4.1
                                   Phase 5 (seam) can start after Phase 1;
                                   5.2/5.3 design benefits from Phase 2.4
                                   (wizard-mode generation gone) being decided.
```

* 1.1 → 1.2 → 1.3/1.4/1.5 (parallelizable), 1.6/1.7 last.
* Phase 1 status: **1.1–1.7 all DONE — Phase 1 (G0) complete**; 1.8
  (post-Phase-1 UI-guard addendum, 2026-10-15) also DONE.
* 2.0 unblocks 2.1 scope; 2.1 unblocks 2.2; 2.2/2.3 → 2.4 → 2.5/2.6.
* 3.1/3.2/3.4/3.5 depend on Phase 1 only (amendment 2026-10-19,
  `00-goals.md` §5.7); 3.3 depends on 2.2. Phase 3 status: 3.1, 3.2 DONE.
* 4.x depends on 2.1 (entity) and 3.x (image/saltboot wiring exists).
* 5.1 is independent after Phase 1; 5.2/5.3 are docs, best after 2.4.

## Suggested session split

1. Session: step 1.1 (+1.2 start).
2. Session: steps 1.2–1.5.
3. Session: steps 1.6–1.7 (incl. agent-VM E2E matrix).
4. Session: step 2.0 + 2.1 design.
5. Session: step 2.2 (+2.3 start).
6. Session: steps 2.3–2.4.
7. Session: steps 2.5–2.6.
8. Session: step 3.1 design, then 3.2/3.3 across two sessions.
9. Session: steps 3.4–3.5.
10. Sessions: Phase 4 steps; Phase 5 interleaved when design capacity allows.

E2E validation on the agent VMs follows the protocol in `00-goals.md` §1
and the VM guide [`05-agent-vms.md`](05-agent-vms.md) (two VMs, recycled at
any time — run the tooling check first; coordinate across parallel agents).

---

## §5 Open questions register

### Feature tiering (decided in step 2.0; initial proposals here)

| Feature | Initial proposal | Decision |
|---|---|---|
| Crypto keys (GPG/SSL) | must-keep (registration needs it) | — |
| Kickstart sessions (status/cancel) | must-keep | — |
| SSM bulk scheduling | must-keep | — |
| Scripts (pre/post) as entities | under review (raw files embed scripts; entities only matter for wizard templating) | — |
| Variables | under review | — |
| Cobbler snippets | under review (registration snippets stay; user snippet UI?) | — |
| File preservation lists | under review | — |
| IP ranges (bare metal) | under review | — |

### Technical questions — resolved 2026-10-07 (promoted to `00-goals.md` §5.8)

* Kernel options vs cobbler `tftpgen` → builders centralize per-distro/breed
  enhancement; saltboot's generic-breed pattern is the reference.
* Agama URL scheme → `http://` for now; configurability deferred to
  post-Cobbler.
* Raw profile storage → on disk until G6.
* `useonlinerepo` → preserved (SLE15 + synced channels).
* Registration snippets → unchanged until G6.
* Proxy contract → older proxy container must keep working with newer server.
* Ubuntu/Debian → non-existent today; from-scratch work in the future.
* Cobbler-exit activation-key delivery → G6 discovery item (step 5.2).

## Changelog

* 2026-10-07 — planning round 1: goals locked (`00-goals.md`), phases/steps
  defined.
* 2026-10-07 — planning round 1 feedback: E2E protocol added (single shared
  agent VM); cobbler PR #177 requirement for Agama documented; SLE16 baseline
  finalized (`opensuse16.1` + `suse` breed + `http://`); technical open
  questions answered and promoted to decisions; Phase 1 behavioural reference
  extracted to `20-kernel-options.md`.
* 2026-10-08 — planning round 2 (step 1.1 design): routing is install-type-only
  (no breed fallback, unknown types throw), Debian builder dropped,
  `profileOptions` empty until G6, rhel6 compat dropped; step 1.5 narrowed
  accordingly. Design: `30-step-1.1-builders.md`.
* 2026-10-08 — step 1.1 implemented and verified (34 DB-free tests,
  checkstyle clean).
* 2026-10-08 — planning round 3 (step 1.2 design): edit-path merge decided —
  the merge runs on create and edit via `BaseTreeEditOperation#store` (fixes
  the XML-RPC update option-wipe; documented behavior change: deleted
  computed options re-appear on the next edit), serverFqdn fallback via
  `ConfigDefaults.getJavaHostname()`, DB-backed tests to run on the agent VM
  (`mgradm stop` frees port 5432; deployed `uyuni-db` lacks `opensuse16.1`).
  Design: `40-step-1.2-distro-path.md`.
* 2026-10-08 — step 1.2 implemented and verified: edit-path merge live
  (create + edit + XML-RPC update/rename), `ks=` defect fix in the RHEL
  builder, SUSE tree-file fixture fix, 57 tests green on the agent VM
  (dedicated pgsql-4eclipse container after `mgradm stop`), full-core
  checkstyle clean. Status → DONE.
* 2026-10-08 — planning round 4 (step 1.5 design): profile-options wiring at
  the `updateCobblerFields` convergence point (single writer: the profile
  details UI; automated callers set no options); `postKernelOptions` stays
  raw; routing failures propagate (no catch); shared `KickstartHostHelper`
  extracted from the step-1.2 FQDN logic. Design:
  `50-step-1.5-profile-path.md`.
* 2026-10-08 — step 1.5 implemented and verified: `KickstartHostHelper` added
  (and `BaseTreeEditOperation#getServerFqdn` delegating to it, behavior
  unchanged), `updateCobblerFields` routes the user-typed profile kernel
  options through `KernelOptionsList` + empty-until-G6 `profileOptions` merge.
  67 tests green on the agent VM (`mgradm stop` → pgsql-4eclipse container,
  branch schema), `mvn -f java compile checkstyle:checkstyle` clean
  (checkstyle now runs via Maven; ant no longer used). Status → DONE.
* 2026-10-08 — step 1.5 follow-up (review): `KickstartHostHelper` reduced to
  a single `getServerFqdn(String)` — provided FQDN is canonicalized,
  null/empty falls back to the configured Java hostname unchanged (protects
  against container-internal resolution); blank-`serverName` tree path now
  uses the configured hostname as-is (parity delta in the step doc §4).
  Re-verified: 44 DB-free tests local, 67 DB-backed on the agent VM.
* 2026-10-09 — planning round 5 (steps 1.3 + 1.4 design, combined in one
  session): per-OS `mediaOptions` contract method — `install=` (AutoYaST),
  `inst.repo=` (RHEL, new session tracking), `inst.install_url=` (Agama
  product-default override) — so the per-session tinyurl media tracking works
  for every OS; `self_update` stays on the system record with schedule-user
  re-evaluation (custom installer-updates children are org/user-tied);
  suse-breed gate dropped for install-type routing; standalone
  `KernelOptionsHelper` (text→textmode transform, nopxe URL); RHEL
  `systemOptions` stays `inst.ks.sendmac inst.ks=` (user-locked — the
  step-1.2 "repaired to ks=" note was wrong and is corrected above).
  Research findings: tinyurl = `DownloadFile` kickstart session progress
  tracking (`packageFetchCount`/state), orthogonal to the proxy FQDN rewrite
  in `tftp_wrapper.py`; `mediaPath != null` ⟹ `ksData != null`.
  Design: `60-step-1.3-1.4-system-and-initiate-path.md`.
* 2026-10-09 — steps 1.3 + 1.4 implemented and verified: generic system path
  (media override + schedule-time self_update re-evaluation, SLE16
  `inst.self_update=` fix, RHEL `inst.repo=` session tracking), initiate path
  composed via `systemOptions` + `KernelOptionsHelper` (textmode + nopxe
  URL), dead branches removed. Supporting fixes: mock
  `get_item_resolved_value` repaired (with cycle guard for the saltboot
  name-collision fixtures), `KernelOptionsList#getValue` added,
  `SystemHandlerProvisioningTest` expectation updated to the intended
  `inst.repo=` override. Agent VM window 10:20–11:00 CEST (mount via `:Z` —
  SELinux enforcing on the VM): 126 + 109 tests green, checkstyle clean, no
  server   deployment. Statuses → DONE.
* 2026-10-09 — retrospective amendments after steps 1.3/1.4: session-protocol
  rule added (`00-goals.md` §1.2 — verify doc/changelog claims against
  code/git before designing on them; both the step-1.2 "ks= repair" note and
  the spec's `KernelOptionsList.getValue` entry had drifted);
  `20-kernel-options.md` gained §9 test-infrastructure notes (cobbler mock
  semantics incl. the repaired resolved-value merge, fixture cookbook,
  system-path regression map); `AGENTS_CUSTOM.md` §3 gained the VM quirks
  (SELinux enforcing → `:Z` mounts, JDK 17 path, rsync-only worktree) —
  outside the plan docs.
* 2026-10-09 — planning round 6 (step 1.6 design): remaining §7 gaps
  enumerated against the steps-1.1–1.5 coverage; opt-in real-cobbler test
  mode designed and dry-run-validated on the agent VM (cobbler 3.3.3 in the
  pgsql-4eclipse image, apache `/cobbler_api` proxy, accept-all auth shim for
  the `authentication.spacewalk` callback protocol, `settings.d/*.settings`
  glob, host fixture-dir bind mounts). Decisions: `-Duse.real.cobbler=true` +
  availability assumption (CI unchanged), phase-1 suites only, per-test
  cobbler cleanup mandatory (fixed test MAC), scripts under
  `java/buildconf/test/cobbler/`, protected `listAllChildren` seam for the
  DB-free org-global branch test, mock-vs-real deltas are findings not
  relaxations. Design: `70-step-1.6-tests.md`. Status → IN PROGRESS.
* 2026-10-09 — step 1.6 implemented and verified: all §7 gap tests added;
  real-cobbler mode live (`use.real.cobbler` property, shim auth, container
  setup script, per-test cleanup, SLE16 capability probe). Real-mode audit
  surfaced and resolved: unordered option maps (sorted-token assertions),
  blank values round-trip as `~`, template/kernel path validation at
  modify/save, two container-only cobbler 3.3.3 patches (copyfile samefile
  on missing dst; sync-race on the per-distro copy). Agent VM window
  13:10–17:05 CEST: 161 mock tests + 50/55 real-mode tests green
  (5 SLE16 skips, PR #177 caveat), checkstyle clean, deployed stack
  restored. Status → DONE — Phase 1 has only 1.7 (cleanup + E2E matrix)
  left.
* 2026-10-12 — planning round 7 + step 1.7 (cleanup & verification):
  dead-code inventory verified against the tree (the legacy string building
  was already deleted in steps 1.2–1.4; 7 unused members remained — scope
  A+B+C locked incl. the test-only ones, `getValue` stays); E2E matrix run
  for real with fake content through the real flow (custom channels + dummy
  kernel/initrd layouts): SLES15 tree/profile/schedule + cobbler records,
  RHEL7 `inst.repo=`, salt-initiate pillar with the full composed boot
  line, SLE16 attempt-and-record (deployed cobbler lacks PR #177 — PRs
  merged, release syncs with the code release). First backend deploy of the
  branch on the agent VM; deployment quirks documented in `AGENTS_CUSTOM.md`
  §3 (`--health-on-failure=stop` container cycling wipes deploy writes;
  deploy.sh `-r` restart path triggers it; direct systemctl restarts are
  safe; a version-matched stack still needs the branch's
  `upgrade/next` + access-data SQL). Agent VM window ~09:40–11:05 CEST:
  180 mock + 50/55 real-mode tests green, checkstyle clean, stack restored
  (spacewalk-java-5.3.1-260400.1.2.uyuni6, cobbler 3.3.3-…uyuni6). Design
  and results: `80-step-1.7-cleanup-verification.md`. Status → DONE —
  **Phase 1 (G0) complete**; next: step 2.0 (feature tiering decisions).
* 2026-10-15 — step 1.8 addendum (post-Phase-1 live-test finding): web-UI
  distro create/edit NPEd when the posted `installtype` did not resolve
  (unguarded null install type, pre-existing legacy code; XML-RPC path was
  already guarded). Fix: `BaseTreeAction#processCommandSetters` returns a
  `ValidatorError` (`kickstart.tree.invalidinstalltype`, new key en_US/de)
  instead of setting null — action-level only, no domain hardening (user
  decision). Closed the step-1.7 UI-E2E gap: web-UI create/edit matrix run
  on the agent VM against the branch-deployed backend (SLES15/RHEL7 create
  green incl. DB+cobbler kopts; SLE16 = documented cobbler PR #177 caveat;
  negative rows clean; edit-path add-missing recompute verified). New
  `BaseTreeActionTest` (first Struts-action-level test, jMock pattern);
  16 tree/action tests green on the VM; compile + checkstyle clean.
  Deployed-code fingerprint check added to `AGENTS_CUSTOM.md` §3. Design
  and results: `85-step-1.8-ui-installtype-guard.md`. Status → DONE.
* 2026-10-19 — planning round 8 (phase order): user decision to skip Phase 2
  for now and start Phase 3. `00-goals.md` §5.7 amended — G3 backend steps
  (3.1, 3.2, 3.4, 3.5) may precede G1+G2; step 3.3 (React UI) stays blocked
  on step 2.2. Dependency map updated.
* 2026-10-19 — step 3.2 implemented (see step doc `95-…`), E2E partially run.
* 2026-10-19 — step 3.1 (saltboot deployment model design) done: two entity
  families (`suseSaltbootBranch` with proxy FK → primary FQDN, hierarchical
  `suseSaltbootConfig` with JSONB partitioning + group/system assignments with
  priority) plus `suseSaltbootTerminal`; deep merge by disk/partition IDs with
  null tombstones; resolution system > most-derived > priority > notification;
  lax boot image refs; containerized proxies only; idempotent `UpgradeCommand`
  migration + formula-hook dual-write. Decisions promoted to `00-goals.md`
  §5.9. Verification correction recorded: today's formula merge replaces
  `edit-group` values and keeps one group's data per formula. Design:
  `90-step-3.1-saltboot-model.md`. Status → DONE (design only, no code).
* 2026-10-19 — planning round 9 (step 3.3 skipped, React UI not done; step
  3.4 design): saltboot builder joins the `KernelOptionsBuilder` interface
  (bound context, no signature change), builder user = creator of the proxy,
  terminal options from a persisted `SaltbootTerminal` (part of the 3.5
  hand-off pulled forward), recomputed branch options on system records,
  `Map` transport kept. Doc drift recorded: `KernelOptionsList#applyOverrides`
  listed in `20-kernel-options.md` §1 does not exist. Design:
  `97-step-3.4b-saltboot-kernel-options.md`. Status → IN PROGRESS.
* 2026-10-19 — planning round 9, continued: user decision to give saltboot
  distros a Java-side representation now (Cobbler exit needs it) — step 3.4
  split into **3.4a** (image-based `KickstartableTree`: nullable
  `channel_id` + `image_id`, `saltboot` install type, breed `generic`) and
  **3.4b** (builder, now routed by install type). `generic_rpm` rejected
  (routes to the RHEL builder). Survey: Python consumers and cobbler sync
  queries are channel-scoped (safe); 7 Java lookups and the install-type
  list need image-tree filters; reposync's `(org_id, label)` uniqueness
  motivates a partial label index. Design: `96-step-3.4a-saltboot-image-tree.md`
  (T1–T3 user, T4–T7 proposed).
* 2026-10-20 — step 3.4a-1 implemented and verified on the agent VM (details
  under step 3.4a; VM build-cache drift noted in `96-…` changelog).
* 2026-10-20 — planning round 10 (session 3.4a-2): user decisions L1
  (generated tree from inspect + upgrade task), T6 revised (image trees own
  their cobbler distro; `createSaltbootDistro` keeps the profile logic),
  L3 (own generated label reserved); L4–L9 proposed; O3 (b) — no wider
  label reservation, risk documented in the step doc §6. 3.4b hand-off
  noted (`distroOptions(tree)` feeds the generated tree). Plan:
  `96-step-3.4a-saltboot-image-tree.md` §5b.
* 2026-10-22 — agent-VM investigation (prerequisite for the 3.4a-2/3.4b E2E):
  agentvm had been recycled (stock Uyuni 5.3.1, clock 20 days behind, no JDK
  17/Maven/obs-to-maven); agentvm2 still had the toolchain. Root causes of
  the earlier VM trouble documented: obs-to-maven failures = random redirects
  of the Leap repo to proxy-blocked mirrors (only `ftp.gwdg.de` allowed);
  test-compile failures = upstream classpath shadowing of the Servlet 6.1 API
  by `jakarta.servlet-api` 6.0.0 (via the OBS `jstl-api` pom); Maven Central
  is reachable as `repo.maven.apache.org`. New guide `05-agent-vms.md` plus
  idempotent scripts under `autoinst_plan/vm/` (tooling check, sync, build
  prep, test DB reset, branch deploy). Validated: tooling + `mvn -f java
  package` on agentvm from an empty `~/.m2`; 54 saltboot/image-tree tests and
  checkstyle on agentvm2. Not yet validated: the branch deploy script (run
  blocked by the permission classifier; agentvm still on stock 5.3.1).
