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
* On the way: step-1.1 defect repaired (RHEL `systemOptions` emitted `inst.ks=`
  instead of the spec/legacy `ks=`); test fixture gap fixed
  (`TreeOperationTestBase` now also creates the SUSE loader kernel/initrd
  layout — SUSE-type `store()` used to fail validation).
* Verified on the agent VM (dedicated pgsql-4eclipse container after
  `mgradm stop`; branch schema incl. `opensuse16.1`): 57 tests green,
  full-core checkstyle audit clean. PXE E2E matrix stays with step 1.7.

### Step 1.3 — System path (status: NOT STARTED)

* Replace `CobblerSystemCreateCommand#processKernelOptions` (suse-breed
  `install=<mediaPath>` + `self_update` logic) per `20-kernel-options.md`
  §5.2; keep `kernelOptionsPost` handling; respect the resolved
  `self_update=0` opt-out.
* `KickstartScheduleCommand#getExtraOptions` continues to produce the user
  part (`ksdevice=` + custom options) for now (full resolution in step 2.6).

### Step 1.4 — Initiate path (status: NOT STARTED)

* Replace `KickstartInitiateAction#buildKernelOptions` and the hand-rolled map
  serializer per `20-kernel-options.md` §5.3: feed resolved cobbler options
  into `KernelOptionsList`, shared SUSE `text`→`textmode` helper (added here —
  first caller), compose via the builder's `systemOptions`, keep `info=`
  (nopxe URL) behavior.
* Drop the dead debian/ubuntu branch (no debian/ubuntu install type exists)
  and the rhel6 `kssendmac` branch (compat dropped — always
  `inst.ks.sendmac`).
* Route SLE16 by install type (tree resolved from the cobbler distro name in
  `prepareCobblerBoot`) — breed is `suse` for both SLES15 and SLE16.

### Step 1.5 — Profile path (status: NOT STARTED)

* Wire `profileOptions` handling into profile create/edit:
  `KickstartDetailsEditAction` → `CobblerProfileCommand#updateCobblerFields`
  (spec: `20-kernel-options.md` §5.4). Kernel options edited in the profile
  details UI flow through `KernelOptionsList` instead of raw strings.
* Narrowed by the step 1.1 decision: no `inst.auto`/`inst.auto_insecure`
  merge — `profileOptions` is empty until the Cobbler exit (G6), cobbler owns
  the installer-selection option on PXE entries
  (`30-step-1.1-builders.md` §1.4).

### Step 1.6 — Tests (status: NOT STARTED)

* Per `20-kernel-options.md` §7: pure-logic builder/factory tests, entity
  tests for install-type routing, call-site parity tests (legacy string
  equality modulo documented whitespace normalization), idempotency tests.
  DB/Cobbler-free where possible (CI runs the rest).

### Step 1.7 — Cleanup & verification (status: NOT STARTED)

* Remove dead string-building code after all sites migrated; checkstyle; E2E
  regression matrix per `20-kernel-options.md` §8 on the agent VM (mind the
  cobbler PR #177 caveat for SLE16 PXE).

### Phase 1 explicit deferrals (documented per guardrail)

* `ScheduleKickstartWizardAction#parseKernelOptions` / `SSMScheduleCommand`
  option resolution — wizard UI dies in Phase 2 (step 2.6).
* Saltboot PXE option maps (`SaltbootUtils`, `PXEEventMessageAction`) —
  Phase 3, step 3.4.
* `CobblerDistroSyncCommand#backsyncKernelOptions` reverse-sync — keep
  behavior; revisit under the G6 seam (step 5.1).

---

## Phase 2 — Kickstart modernization + React UI (G1 + G2)

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

### Step 3.1 — Saltboot deployment model design (status: NOT STARTED)

* Model saltboot deployment as an autoinstallation deployment type: target
  (group/branch), partitioning model (reuse the saltboot form model),
  boot image (from `ImageInfo`), deployment state. Decide DB schema, relation
  to the unified profile entity, and migration of existing group formula data
  (`formula-saltboot-group` pillars).

### Step 3.2 — Java domain, schema, API (status: NOT STARTED)

* Implement the model + XML-RPC/REST API; keep `SaltbootUtils` Cobbler entry
  generation working, routed through the new domain.

### Step 3.3 — React UI (status: NOT STARTED)

* Saltboot area inside the new autoinstallation UI: deployment profiles,
  group/terminal targeting, image selection, status; formula form replaced.

### Step 3.4 — PXE/kernel options via builders (status: NOT STARTED)

* Route `SaltbootUtils`/`PXEEventMessageAction` option construction
  (`panic=60 splash=silent`, per-terminal params, master/minion prefixes)
  through the G0 builder hierarchy (new saltboot builder or extension),
  closing the Phase 1 deferral. Saltboot's `generic`-breed "own the whole
  command line" pattern is the reference for post-Cobbler option ownership
  (`20-kernel-options.md` §6).

### Step 3.5 — Trigger/orchestration model (status: NOT STARTED)

* Deployment triggering via the autoinstallation action/schedule flow instead
  of formula-save hooks; preserve the proven `suse/manager/pxe_update` event
  loop (`PXEEventMessageAction`) and force-redeploy/repartition flag semantics.

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
      │                            ├──► Phase 3 (saltboot) ──► Phase 4
      │                            │
      └── builders reused in 3.4/4.2/4.4
                                   Phase 2.1 (unified entity) ──► Phase 4.1
                                   Phase 5 (seam) can start after Phase 1;
                                   5.2/5.3 design benefits from Phase 2.4
                                   (wizard-mode generation gone) being decided.
```

* 1.1 → 1.2 → 1.3/1.4/1.5 (parallelizable), 1.6/1.7 last.
* 2.0 unblocks 2.1 scope; 2.1 unblocks 2.2; 2.2/2.3 → 2.4 → 2.5/2.6.
* 3.x depends on 2.x UI foundation and Phase 1 builders.
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

E2E validation on the agent VM follows the protocol in `00-goals.md` §1
(single VM, manually recycled — coordinate across parallel agents).

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
