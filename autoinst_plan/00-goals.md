# Autoinstallation Overhaul — High-Level Goals

> **Cross-session contract.** Every session working on autoinstallation must read
> this document first. The goals here change only by editing this file. The
> working breakdown into implementation steps lives in
> [`10-roadmap.md`](10-roadmap.md); the Phase 1 behavioural reference is
> [`20-kernel-options.md`](20-kernel-options.md).

| | |
|---|---|
| Branch | `sles16_agama` |
| Created | 2026-10-07 |
| Status | Planning round 1 complete; amended 2026-10-19 (G3 backend before G1+G2, §5.7; saltboot model decisions, §5.9) |
| Companion docs | [`10-roadmap.md`](10-roadmap.md) (steps, status, open questions), [`20-kernel-options.md`](20-kernel-options.md) (Phase 1 reference) |

---

## 1. Purpose and session protocol

Uyuni's autoinstallation support grew out of Spacewalk-era Kickstart plus SUSE
additions (AutoYaST, retail Saltboot), with Cobbler as an external rendering/PXE
engine and a legacy JSP UI. The code is fragmented (kernel options are
manipulated as strings in ~13 places), the wizard-based Kickstart UI is outdated,
Saltboot lives outside the autoinstallation workflow, and there is no support
for modern generic installation mechanisms.

This overhaul is too large for one session. The goals below are the shared
contract that keeps individual sessions aligned so that no step blocks or
hinders another.

**Session protocol:**

1. Read this file (`00-goals.md`) and the relevant phase in `10-roadmap.md`. Read `AGENTS_CUSTOM.md` in the repository root to know the environment.
2. Pick **one** roadmap step. Do focused research if needed. When a design
   builds on a claim from the plan documents or changelogs, verify it against
   the code/git history first — documents can drift from the tree (a recorded
   fix may never have landed, a listed API may not exist).
3. Write/extend the step's plan (a section in `10-roadmap.md` or a dedicated
   step doc linked from it) *before* implementing.
4. Implement, verify (`mvn compile`, checkstyle, unit tests; frontend build for
   UI steps), update the step status in `10-roadmap.md`.
5. If a required change would violate a guardrail (§4) or a locked decision
   (§5), stop and update this document first, with justification.
6. For steps that change runtime behavior, validate end-to-end on the provided
   agent VM (see `AGENTS_CUSTOM.md` §3). There is exactly **one VM** and it is
   **manually recycled** — when multiple agents work in parallel, they must
   coordinate its use (deployment/verification windows) and record the deployed
   package version together with their results. Note the VM's Cobbler may lack
   the Agama support PR (see §5.1) — check before SLE16 E2E validation.

---

## 2. Goal overview

| ID | Goal | Horizon |
|----|------|---------|
| G0 | Unified kernel-options handling (foundation) | near-term |
| G1 | Kickstart modernization — raw upload only, wizard dropped | near-term |
| G2 | UI modernization — React replaces all autoinstallation JSP | near/mid-term |
| G3 | Saltboot integrated as first-class autoinstallation workflow | mid-term |
| G4 | Generic installation mechanisms (cloud-init, ignition, combustion) on a unified profile entity | mid-term |
| G5 | Image building integrated: built artifacts become autoinstallation options | mid-term |
| G6 | Cobbler exit preparation (design only; removal is long-term) | long-term |

Implementation order (locked, §5.7): **G0 → G1+G2 → G3 → G4+G5 → G6-prep**
(amended 2026-10-19: G3 backend may precede G1+G2, see §5.7).

---

## 3. The goals

### G0 — Unified kernel-options handling

Kernel command-line options for installation flows are currently built as
ad-hoc strings in many places (inventory in §6.3). All of this collapses into a
single, OS-class-aware builder hierarchy:

* Package `com.suse.manager.autoinstallation` (namespace already started on this
  branch with the `KernelOptionsList` value object).
* One builder per OS class (RHEL-like, AutoYaST/SLES15-, Agama/SLE16,
  Debian/Ubuntu), a shared abstract base, and a factory routed by
  `KickstartInstallType` (breed-based routing only as fallback where no install
  type exists).
* Builders expose semantic composition points: `distroOptions`,
  `profileOptions`, `systemOptions`, `networkBoot`, `localBoot`; the full
  contract, factory routing, and the catalogue of legacy strings to reproduce
  live in [`20-kernel-options.md`](20-kernel-options.md).
* **Post-Cobbler readiness (OQ-1 resolution):** while Cobbler renders PXE
  entries it adds breed-specific options itself (`autoyast=` vs `inst.auto=`
  selection in `tftpgen`); the Salt provisioning path adds them in Uyuni. The
  builders must therefore centralize per-distribution/breed option enhancement
  so the G6 switch does not require call-site surgery. Saltboot already owns
  its whole kernel command line via the `generic` breed (avoiding Cobbler
  interference) and is the reference pattern for this.
* **Extension rule:** a new OS or installation mechanism adds *one builder
  class* (or one option set in an existing builder) — never a new call-site
  string manipulation.

Success criterion: all inventory sites of §6.3 either use the builders or are
consciously deferred to a later phase with a documented reason.

### G1 — Kickstart modernization (raw-upload only)

The Kickstart wizard (3-step creation wizard + wizard-mode editing tabs that
generate Cheetah templates rendered by Cobbler) is dropped. **Only uploading of
pre-created files is supported**, matching the current AutoYaST workflow.

* Profile create = select channel/distro/virt type + upload file (or paste
  content); profile edit = raw file edit + download.
* The wizard/raw distinction (`rhnKSData.ks_type`) collapses to raw.
* Profile-level support features (scripts, variables, snippets, file
  preservation, crypto keys, IP ranges, sessions) are **tiered** and decided
  *feature-by-feature* (§5.3); the tiering register lives in the roadmap open
  questions.
* The data model must stay migration-friendly toward the G4 unified entity:
  no new wizard-era schema on `rhnKSData`.
* Dropping the wizard also removes the biggest Cheetah/`KickstartFormatter`
  dependency — the prerequisite for G6's Java-side serving.

### G2 — UI modernization

All autoinstallation JSP/Struts pages (profiles, distributions, snippets, keys,
file preservation, IP ranges, sessions, SSM scheduling) are replaced by React
pages.

* Pattern: the *images* feature (`web/html/src/manager/images/` + Spark
  controllers in `java/core/src/main/java/com/suse/manager/webui/controllers/`)
  is the in-repo template for page structure, tables, forms, and API wiring.
* The new UI must render profile types **metadata-driven** so G4's new types
  (cloud-init, ignition, combustion) and G3's saltboot deployments appear in
  the same pages without per-type UI forks.
* Legacy menu entries (`MenuTree.getSystemsNode()` "Kickstart" node) are
  restructured into the new autoinstallation area.

### G3 — Saltboot integration (full workflow)

Saltboot (retail POS image deployment) becomes a first-class autoinstallation
workflow instead of a separate group-formula configuration:

* Saltboot deployment is modeled in the Java autoinstallation domain (deploy
  target, partitioning model, boot image, deployment state) with API and React
  UI in the new autoinstallation area (model decisions: §5.9).
* The external saltboot formula states remain only as the **execution engine**;
  configuration no longer lives as formula form data on groups.
* PXE/kernel-option handling goes through the G0 builders.
* The trigger model moves to the autoinstallation action/schedule flow
  (instead of formula-save hooks), while preserving the proven
  `pxe_update` event loop for flipping terminals back to disk boot.
* Existing `SaltbootUtils` Cobbler entry generation is kept working during the
  transition and re-routed, not duplicated.

### G4 — Generic installation mechanisms on a unified profile entity

cloud-init, ignition, and combustion (and future mechanisms) become supported
installation/configuration profile types:

* A **new unified autoinstallation profile entity** (type-based:
  kickstart/autoyast/agama/cloud-init/ignition/combustion/...) is introduced;
  `rhnKSData` and raw profiles migrate behind it over time. This is the
  long-term replacement of the Kickstart-centric data model.
* Upload/storage/download/serving is uniform across types; serving endpoints
  follow the existing `/ks/cfg`-style public URL conventions.
* Boot-parameter needs of each mechanism are added in the G0 builders.
* Initial focus is SLE16/Edge-style workflows (images + ignition/combustion),
  where these mechanisms are actually consumed today.

### G5 — Image building integration

When a supported image artifact is built (Kiwi OS image with saltboot payload,
EIB/combustion artifacts, later ignition-carrying images), it is automatically
registered as an autoinstallation option:

* Hooks into the existing build-result pipeline
  (`ImageBuildAction` → `ImageInfo`/`ImageFile` → OS-image pillar).
* Built artifacts appear as selectable targets in the new autoinstallation UI
  (e.g. saltboot deployment defaults, generic-mechanism deployments).
* No manual re-import or formula hand-configuration for the supported cases.

### G6 — Cobbler exit preparation (long-term, design only)

Long-term goal: drop Cobbler; generate grub entries and maintain
kernel/initrd/grub artifacts in Java. **These sessions only prepare for it** —
no cobbler removal is implemented now.

* Introduce an abstraction seam (`InstallServer`/`BootServer`-style interface)
  over `org.cobbler` + the cobbler command classes so future sessions can
  implement a Java back-end behind it.
* Design (document, not implement): Java-side grub entry generation into
  tftpboot, profile serving without Cobbler/Cheetah (unlocked by G1),
  kernel/initrd lifecycle, proxy `tftp_wrapper.py` compatibility.
* **grub only** — no pxelinux support; the design must allow future expansion
  (e.g. UKI-based boot).

---

## 4. Cross-cutting guardrails

These rules are what keep the goals from blocking each other. Every
implementation step is checked against them:

1. **One place for boot parameters.** Any new OS/mechanism boot parameter goes
   through the G0 builders. Never introduce new string-manipulation call sites,
   even temporarily.
2. **Migration-friendly raw profiles.** G1 must not add schema or behavior to
   `rhnKSData` that makes the later migration to the G4 unified entity harder
   (no wizard-era columns, keep ks_type semantics collapsible).
3. **Metadata-driven UI.** G2 pages render profile types from type metadata /
   entity fields; adding a G4 type or a G3 deployment kind must not require new
   UI per type beyond configuration/metadata.
4. **No new Cobbler coupling.** No new feature may add a dependency that only
   works through Cobbler specifics (Cheetah snippets, cobbler-only fields,
   `/cblr/svc` URLs). New functionality must fit the future InstallServer seam
   (G6). Reuse of existing cobbler plumbing during transition is fine.
5. **Public behavior preserved.** XML-RPC API and spacecmd behavior stays
   compatible unless a goal explicitly changes it; removals are announced by
   deprecation in docs/changelogs.
6. **Schema discipline.** All schema changes are additive with proper upgrade
   scripts in the current upgrade directory; never edit generated
   `main.sql`/blend outputs directly; add `.changes.<author>.<topic>`
   fragments, never touch master `.changes`.
7. **Scope discipline.** Smallest complete change per step; no opportunistic
   modernization of adjacent code; each step compiles, passes checkstyle, and
   carries its own tests (DB/cobbler-free where possible).
8. **Verify against deployed instance** (VM, `AGENTS_CUSTOM.md` §3) whenever a
   step changes runtime behavior; record deployed package version in reports.

---

## 5. Locked design decisions

Decisions made in planning round 1. Changing any of these requires editing this
document and noting it in the roadmap changelog.

### 5.1 SLE16/Agama identity — committed code wins

The branch's committed behavior is the baseline:

* Install type label: **`opensuse16.1`** ("SUSE Linux Enterprise 16"), as
  committed in `rhnKSInstallType.sql` +
  `schema/spacewalk/upgrade/next/001-add-sle16-install-type.sql`.
* Cobbler breed for SLE16 stays **`suse`** (as committed in
  `KickstartInstallType.getCobblerBreed()`), *not* `generic`.
* URL scheme: **`http://`** without `?ssl_verify=no` for the Agama
  `root=live:` squashfs URL (as committed in `TreeCreateOperation`).
* The Agama kernel-options builder must be written to reproduce the committed
  strings (`root=live:`, `inst.self_update=`, `inst.auto=`).
* Factory still routes `isSLES16OrGreater()` → Agama builder **before** the
  generic SUSE branch; routing is by install type, breed remains `suse`.
  Implication: Cobbler continues to apply suse-breed behavior; the Agama
  builder must be idempotent against that (see guardrail 1 and roadmap step
  1.1). Routing by breed alone would misroute SLE16 to the AutoYaST builder.
* **Cobbler dependency:** full Agama PXE install requires a cobbler build
  containing [openSUSE/cobbler PR #177](https://github.com/openSUSE/cobbler/pull/177)
  ("openSUSE Agama Support for 3.3.x", port of cobbler#4001): Leap 16 distro
  signature + Agama templates, and `tftpgen` emitting `inst.auto=` for
  suse-breed distros whose os_version is not on the AutoYaST denylist. The
  cobbler deployed on the agent VM may lack this — check before any SLE16 E2E
  validation.
* **URL scheme configurability deferred:** Agama accepts `http://` (tested,
  works); some users mandate `https://`, at least one mandates `http://`.
  Making the scheme configurable is postponed until Uyuni controls the full
  kernel command line (post-Cobbler, G6); today the PXE-time `inst.auto=` is
  added by cobbler and outside Uyuni's domain.

### 5.2 Kickstart = raw upload only

Wizard UI and wizard-mode file generation are dropped; only pre-created file
upload is supported (AutoYaST-style).

### 5.3 Feature tiering for profile-level features

Support features that currently exist in wizard mode are decided
feature-by-feature, not wholesale. Each feature gets an explicit entry in the
roadmap open-questions register with one of: *must-keep (migrate to React)*,
*drop*, or *under review*. The initial register is in `10-roadmap.md` §5.

### 5.4 Saltboot = full workflow integration

Saltboot deployment becomes a first-class autoinstallation deployment type in
the Java domain (G3); formula states remain execution engine only.

### 5.5 Generic mechanisms = new unified entity

cloud-init/ignition/combustion are modeled on a new unified type-based
autoinstallation profile entity; extending `rhnKSData.ks_type` was rejected
(legacy naming would persist forever and the CHECK-constraint approach limits
type metadata).

### 5.6 Cobbler exit = seam + design only

No cobbler removal or replacement implementation in these sessions; only the
abstraction seam and design documents.

### 5.7 Implementation order

`G0 → G1+G2 → G3 → G4+G5 → G6-prep`, as elaborated in `10-roadmap.md`. UI work
(G2) lands early as the foundation all later features surface through.

**Amendment 2026-10-19 (user decision, planning round 8):** G3 *backend* work
— model design, schema, Java domain, API, saltboot kernel-option builder, and
trigger model (roadmap steps 3.1, 3.2, 3.4, 3.5) — may proceed before G1+G2.
G3 *UI* (step 3.3) still requires the G2 UI foundation (step 2.2). The G3
model is designed forward-compatible with the G4 unified profile entity
(step 2.1) but does not depend on it. Justification: step 3.4 depends only on
G0, and a saltboot configuration is a deployment kind rather than an uploaded
profile file, so it can be modeled independently of the profile entity
(`90-step-3.1-saltboot-model.md` §2.7).

### 5.8 Resolved technical decisions (planning round 1)

Answers to the initial open-questions register, promoted to decisions.

| Topic | Decision |
|---|---|
| Kernel options vs Cobbler `tftpgen` | While Cobbler renders PXE entries it adds breed-specific options itself (`autoyast=` vs `inst.auto=` per os_version denylist, PR #177); the Salt provisioning path adds `autoyast=`/`inst.auto=` in Uyuni (already implemented). Builders must centralize per-distro/breed enhancement so the G6 switch needs no call-site surgery. Saltboot owns its whole command line via the `generic` breed (reference pattern). |
| Agama URL scheme | `http://` works and is tested; `https://` mandated by some users, `http://` by at least one → configurable scheme deferred until Uyuni controls the full kernel command line (§5.1). |
| Raw profile content storage | Stays on disk (Cobbler templates dir) until G6; revisit with the unified-entity serving design (roadmap step 2.1 notes this constraint). |
| `useonlinerepo` flag | Must survive builder refactors — required for SLE15 with synced channels instead of full DVD (`DownloadFile` string dependency, §6.3 item 13). |
| Registration via Cobbler snippets | Stays as-is until G6; the replacement activation-key delivery mechanism is part of G6 discovery (roadmap step 5.2). |
| Proxy compatibility contract | `containers/proxy-tftpd-image/tftp_wrapper.py` may be adjusted, but an **older proxy container must keep working against a newer server** — no breaking changes to served PXE-entry formats; optimizations only (roadmap step 5.3). |
| Ubuntu/Debian autoinstallation | Considered **non-existent**: leftover bits do not work at all. The Debian kernel-options builder is at most a behavior-preserving stub for the initiate path; real support is from-scratch future work outside the current phases. |

### 5.9 Saltboot model decisions (step 3.1, 2026-10-19)

Locked by the user in planning round 8; design and rationale in
[`90-step-3.1-saltboot-model.md`](90-step-3.1-saltboot-model.md).

| Topic | Decision |
|---|---|
| Proxy support | Only **containerized proxies** are supported. No `containerized_proxy` flag in the model; the legacy retail branch-server path (salt reactor `pxe/terminal_entry`) is not modeled. |
| Download server | Free-text `download_server` becomes an **FK to the proxy system**; its **primary FQDN** is used until the parallel multi-FQDN proxy work provides selection flags (extension point reserved). |
| Entity split | Branch settings (`suseSaltbootBranch`, one per branch group) are separate from hierarchical partitioning/image configs (`suseSaltbootConfig`) assigned to groups or systems. |
| Partitioning storage | **JSONB**, validated in Java against the formula form shape. |
| Config hierarchy | Configs have an optional parent; the effective config is a **deep merge keyed by disk/partition IDs** along the chain; JSON `null` tombstones remove inherited entries (enables A/B rollouts that only switch the image). |
| Resolution | System assignment wins; among group assignments the **most-derived** config on one chain wins; unrelated chains use the assignment **priority**; a remaining tie raises a notification and blocks deployment for that terminal. |
| Boot image reference | **Lax string** (`name`, `name-version`, `name-version-revision`), never an FK. |
| Migration | Upgrade-time Java migration via `UpgradeCommand` (idempotent), then **dual-write**: formula-save hooks also write the domain; users unassign the saltboot formulas manually when ready. |

---

## 6. Current-state fact sheet

Condensed research (2026-10-07) so future sessions don't re-explore. Paths
relative to repo root.

### 6.1 Data model

* `java/core/src/main/java/com/redhat/rhn/domain/kickstart/KickstartData.java`
  → table `rhnKSData`, SINGLE_TABLE inheritance, discriminator column
  `ks_type` (`wizard` | `raw`, CHECK constraint in
  `schema/spacewalk/common/tables/rhnKSData.sql`).
* `KickstartRawData` (`ks_type='raw'`): raw text is **not in the DB**;
  `getData()` reads the file named by the Cobbler profile's `kickstart` field
  under `ConfigDefaults.getKickstartConfigDir()` (= `/var/lib/cobbler/templates/`).
* `KickstartableTree` (`rhnKickstartableTree`): `kernel_options`,
  `kernel_options_post`, `base_path`, `install_type` FK, `cobbler_id`.
* `KickstartInstallType` (`rhnKsInstallType`): labels incl. `sles*`,
  `opensuse*` (SLE16 = `opensuse16.1`), `rhel_*`, `generic_rpm`; breeds
  `suse`/`redhat`/`generic`; SLE16 predicates `isSLES16()`/`isSLES16OrGreater()`
  exist on this branch.
* AutoYaST is *not* a separate entity — SUSE profiles are `ks_type='raw'`;
  `KickstartBuilder.buildCommands` refuses to parse XML/JSON content, so
  AutoYaST/Agama files are stored as-is.

### 6.2 Rendering/serving chain (Cobbler-centric)

1. Java writes profile file to `/var/lib/cobbler/templates/{wizard,upload}/`
   (`KickstartFactory.saveKickstartData`; wizard content generated by
   `KickstartFormatter` incl. `$SNIPPET(...)` Cheetah directives).
2. Cobbler renders (Cheetah) and serves
   `/cblr/svc/op/autoinstall/{profile,system}/<name>`.
3. Java proxies/rewrites: `zz-spacewalk-www.conf` rewrites `/ks/cfg/*` →
   `RenderKickstartFileAction`; `KickstartManager.renderKickstart` fetches from
   Cobbler; `/ks/dist/*` → `DownloadFile` serves tree media **directly from
   disk** (no Cobbler).
4. Registration/activation keys ride on Cobbler snippets
   (`java/conf/cobbler/snippets/redhat_register_using_salt` et al.) and
   `redhat_management_key` fields.

### 6.3 Kernel-options inventory (build/transform/dependency sites)

Primary build sites (legacy inline string logic, to be replaced by builders):

1. `manager/kickstart/tree/TreeCreateOperation#store` — distro options
   (`root=live:`+`inst.self_update=` for SLE16, `install=`/`self_update=` for
   SUSE, `inst.repo=` for RHEL8+), persisted into
   `KickstartableTree.kernelOptions`.
2. `manager/kickstart/cobbler/CobblerSystemCreateCommand#processKernelOptions`
   — suse-breed system options (`install=`, `self_update=`).
3. `domain/action/kickstart/KickstartInitiateAction#buildKernelOptions` — Salt
   local-reboot (`text`→`textmode`, `ks=`/`autoyast=`/`inst.auto=`/`url=`,
   debian `auto-install/enable=... url=`, `info=`), hand-rolled map serializer.

Additional touchpoints (consume, persist, or transform):

4. `KickstartScheduleCommand#getExtraOptions` — prepends `ksdevice=` + user
   options; feeds both the cobbler system record and the action.
5. `ScheduleKickstartWizardAction#parseKernelOptions` (+ `SSMScheduleCommand`)
   — resolves distro/profile/custom options from Cobbler (wizard UI; dies with
   G1/G2).
6. `tree/BaseTreeEditOperation` — pushes tree options into Cobbler `Distro`
   (edit-path twin of site 1).
7. `cobbler/CobblerDistroHelper` — sets distro kernel options on create/update.
8. `cobbler/CobblerProfileCommand#updateCobblerFields` — profile-level options
   (the Phase 1 "profile path" target, `20-kernel-options.md` §5.4).
9. `cobbler/CobblerDistroSyncCommand#backsyncKernelOptions` — reverse sync
   Cobbler → DB (CobblerSyncTask).
10. XML-RPC `KickstartTreeHandler` — passes caller kernel options into tree ops.
11. `com/suse/manager/saltboot/SaltbootUtils` + `PXEEventMessageAction` —
    saltboot PXE option maps (`panic=60 splash=silent`, per-terminal params).
12. `KickstartCloneCommand` — copies options on clone.
13. `DownloadFile` — *depends* on the literal string `useonlinerepo` in tree
    kernel options (format coupling to preserve; required for SLE15 with
    synced channels — decision §5.8).

### 6.4 Cobbler footprint (context for G6)

* Version **3.3.3** (SUSE fork). XML-RPC API localhost-only
  (`spacewalk/config/etc/httpd/conf.d/01-cobblerapi.conf`); auth via
  `authentication.spacewalk` module.
* Agama/SLE16 PXE install requires cobbler with openSUSE/cobbler PR #177
  (Leap 16 distro signature, Agama templates/snippets, `tftpgen` emitting
  `inst.auto=` for suse-breed distros whose os_version is not on the AutoYaST
  denylist — see §5.1). Deployment environments (e.g. the agent VM) may ship an
  older cobbler without it.
* Java client: `java/core/src/main/java/org/cobbler/` + commands under
  `manager/kickstart/cobbler/` (profile/distro/system CRUD, power management,
  snippets, sync).
* Taskomatic `CobblerSyncTask` every 5 min (backsync kernel options, re-point
  profiles to newest tree); `KickstartFileSyncTask` regenerates wizard files.
* Cobbler writes PXE menus into `/srv/tftpboot` on `cobbler sync` +
  `cobbler mkloaders`; Uyuni salt states do **not** write PXE files.
* Python usage is minimal: `python/uyuni-cobbler-helper/uyuni_cobbler_helper.py`
  (DB queries imported by Cheetah templates for `autoyast_channels`/`root_ca`
  snippets).
* Saltboot also creates Cobbler distros/profiles/system records
  (`com/suse/manager/saltboot/SaltbootUtils`).
* Proxy: `containers/proxy-tftpd-image/tftp_wrapper.py` fetches PXE entries via
  HTTP and filters/rewrites them (must stay compatible in G6 design).

### 6.5 Saltboot flow (context for G3)

1. Kiwi OS image built on build host → collected to server (`ImageFile`:
   image/kernel/initrd/bundle) → OS-image pillar
   (`SaltStateGeneratorService.generateOSImagePillar`).
2. `SaltbootUtils.createSaltbootDistro/Profile/System` create Cobbler PXE
   entries (boot image kernel/initrd, per-terminal records with
   `root=`/`salt_device=`/MAC).
3. Terminal PXE-boots into saltboot initrd; on minion start
   `RegisterMinionEventMessageAction` assigns branch/terminal groups and
   triggers `state.apply` of the saltboot formula (external states).
4. Formula emits salt event `suse/manager/pxe_update` → `PXEEventMessageAction`
   updates the Cobbler system record (`root=<device>` → boot from disk) and
   resets force-redeploy flags.
5. Partitioning config today = group formula form (`formula-saltboot-group`
   pillar, React formula framework).
6. The legacy retail branch-server path (salt reactor running
   `pxe/terminal_entry` on non-containerized branch servers) is unsupported
   (§5.9); verified current state in `90-step-3.1-saltboot-model.md` §1.

### 6.6 UI state

* All autoinstallation pages are Struts/JSP: 54 action classes +
  31 JSPs (`java/webapp/src/main/webapp/WEB-INF/pages/kickstart/`), struts
  mappings, nav XMLs (`WEB-INF/nav/kickstart_*.xml`). Menu node: Systems ▸
  Kickstart in `MenuTree.getSystemsNode()`.
* Zero React for autoinstallation. Existing React patterns to copy: images
  feature (`web/html/src/manager/images/`, controllers `Image*Controller`),
  formula framework (`web/html/src/components/FormulaForm.tsx`),
  formik forms, Spark controller + jade + `spaImportReactPage` wiring.

### 6.7 Generic mechanisms today

* cloud-init: no product support (test fixtures only).
* ignition: nothing.
* combustion: only as an EIB side-path inside
  `susemanager-utils/susemanager-sls/salt/images/kiwi-image-build.sls`
  (extracts `combustion.iso` into the Kiwi image `/oem`).

---

## 7. Non-goals

* No pxelinux support anywhere in the overhaul (grub only; UKI-extensible).
* No Cobbler removal implementation — preparation only (§5.6).
* No support for running cloud-init on already-registered minions (G4 is about
  installation/bootstrapping profiles, not minion runtime config).
* No acceptance-test work (`testsuite/` runs in CI only).
* No wholesale rewrites of adjacent legacy code beyond what the goals require.
