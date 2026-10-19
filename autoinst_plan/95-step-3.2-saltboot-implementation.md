# Step 3.2 — Saltboot Java Domain, Schema, API

> Implementation plan for roadmap step 3.2. Model, merge/resolution rules and
> migration mapping are fixed in [`90-step-3.1-saltboot-model.md`](90-step-3.1-saltboot-model.md)
> (§2–§5); this document records what verification added or changed and how
> the work is cut.

| | |
|---|---|
| Branch | `sles16_agama` |
| Created | 2026-10-19 |
| Status | DONE (E2E partial) |
| Related | `90-step-3.1-saltboot-model.md`, `00-goals.md` §5.9 |

---

## 1. Verification results (2026-10-19)

Checked against the tree (`583440e9b18`) and `saltboot-formula` 1.1.0 on the
agent VM (read-only). They refine the step 3.1 design:

* **More pillar keys are consumed than §1.1 listed.** The execution engine
  (`_states/saltboot.py`, `states/saltboot/init.sls`) reads: `partitioning`,
  `images`, `boot_images`, `terminal_kernel_parameters`,
  `saltboot:kernel_action` (`kexec`|`reboot`|`fail`), `saltboot:freeze_image`,
  `saltboot:force_redeploy`, `saltboot:force_repartition`,
  `saltboot:download_server` (image URL mangling, `_mangle_url`), plus
  `custom_info:saltboot_*`. `pillar.get('saltboot') is not None` is also used
  as a "managed by the saltboot formula" switch (skips the bundle-sync check),
  so the generator **always emits a `saltboot` dict** for a resolved terminal.
* The `saltboot` formula form (`form.yml`) carries three values the design did
  not model: top-level `terminal_kernel_parameters`, and under `saltboot:`
  `kernel_action` and `freeze_image`. A lossless migration needs them →
  **`suseSaltbootConfig` gains `terminal_kernel_parameters VARCHAR` and
  `freeze_image BOOLEAN`**, both nullable (null = inherit, same rule as
  `kernel_action`). Additive refinement of §2.2, no new decision.
* Partition enums from the form: `format` ∈ {btrfs, ext4, xfs, vfat, swap}
  (blank allowed), `flags` ∈ {swap, raid, bios_grub, esp, boot} (blank
  allowed), `disklabel` ∈ {gpt, msdos, none}, `type` ∈ {DISK, RAID}, RAID
  `level` 1 only. Disk keys: `type device level devices disklabel partitions`
  and, for `disklabel: none`, the partition fields directly on the disk.
* `SaltbootUtils.deleteSaltbootDistro` §1.9 bug confirmed in code: the
  `nameV` profile is re-pointed with `makeCobblerFilterName` instead of
  `makeCobblerFilterNameV`.
* `RegisterMinionEventMessageAction#prepareRetailMinionForSaltboot` already
  calls the full `MinionPillarManager.generatePillar(minion)` after the group
  assignment, so a generator in the full path covers registration.
* XML-RPC (redstone) has no `nil`, so tombstones (JSON `null`) cannot cross
  the XML-RPC boundary: the API maps the string `$delete` to/from `null`.
  Hibernate JSONB keeps real `null`s.

## 2. Decisions taken in this step

| # | Decision | Reason |
|---|---|---|
| S1 | While a saltboot formula applies, `SaltbootUtils` keeps reading the formula pillar; the domain is read when no formula pillar exists. | Dual-write keeps both equal, but `download_server` (free text) vs proxy FK (may be unresolved) differ — formula-first preserves today's `MASTER=` exactly. Domain takes over on formula unassignment, as §4.2 intends. |
| S2 | Permissions: config CRUD/list/get/assignments → **Org Admin** (partitioning may carry `luks_pass`); branch ops, terminal ops → permission on the target group/system (`FormulaUtil` checks, as formulas today). | Closest to the formula permission model; configs are org-wide objects. |
| S3 | API edits of a branch/config/assignment/terminal flag whose target still has a saltboot formula are refused with an explanatory fault (§4.2). | Avoids silent no-ops: the domain generator is suppressed while a formula applies. |
| S4 | `saltboot` pillar category is stored as a **minion pillar row** written by `SaltbootPillarGenerator` (like the other generators) and added to the full path of `MinionPillarManager`. | `suma_minion.py` merges non-formula categories recursively; no Python change. |
| S5 | `resetSaltbootRedeployFlags` additionally clears the domain flags and regenerates the saltboot pillar. | Flags moved to `SaltbootTerminal`; otherwise they would stay set forever. |

## 3. Work cut

1. **Schema** — four tables under `schema/spacewalk/common/tables/`,
   `tables.deps`, idempotent `upgrade/next/` script (tables, migration task).
2. **Domain** — `com.suse.manager.autoinstallation.saltboot`: entities,
   `SaltbootFactory`, DB-free `SaltbootConfigResolver` (merge, chain,
   resolution), `SaltbootPartitioning` validation, `AnnotationRegistry`.
3. **Pillar** — `SaltbootPillarGenerator` + `MinionPillarManager` wiring.
4. **Formula bridge** — `SaltbootFormulaMapper` (formula data → domain),
   `FormulaFactory` dual-write + unassign behavior, `SaltbootDomainMigration`
   + `UpgradeCommand` task.
5. **Cobbler adapter** — `SaltbootUtils` reads branch settings from the
   domain (S1); §1.9 fix; flag reset (S5).
6. **API** — `saltboot` XML-RPC namespace (`SaltbootHandler`), serializers
   if needed, `HandlerFactory` registration.
7. **Tests** — DB-free resolver/merge/validation tests; DB-backed factory,
   migration, dual-write, pillar-generator, handler tests (VM).
8. Changelog fragment, status updates.

## 4. Verification plan

Local: `mvn -f java/core compile checkstyle:checkstyle`, DB-free unit tests.
Agent VM (single shared, `AGENTS_CUSTOM.md` §3): DB-backed suites against the
branch schema; record the deployed versions with the results.

## 5. Results (2026-10-19)

* Unit/integration: 155 tests (saltboot domain, pillar, manager, handler,
  mapper, migration, utils) + 216 neighbouring (formula, notifications,
  register-minion, SystemHandler, RBAC, upgrade) green against the branch
  schema in the pgsql-4eclipse container; `upgrade/next` scripts re-applied
  twice without error. Checkstyle: `mvn -o checkstyle:check
  -Dcheckstyle.config.location=../buildconf/checkstyle.xml
  -Dcheckstyle.suppressions.location=../buildconf/checkstyle-suppressions.xml
  -Dcheckstyle.includeTestSourceDirectory=true` in `java/core` — 0 violations.
* Real server (spacewalk-java-5.3.1-260400.1.2.uyuni6 + overlaid branch
  classes, `tasklist/validation/vm-deploy-core-overlay.sh`):
  `tasklist/validation/saltboot_api_e2e.py` — config API incl. tombstone
  marker, booleans, validation faults: 11/11 green. It then stops at
  `systemgroup.create`: the VM DB has every default access group twice
  (ids 7–12 from a manual data load on 2026-10-12), so
  `AccessGroupFactory.lookupDefault` throws NonUniqueResult — unrelated to
  this step. **Open:** dedupe those rows (`vm-dedupe-accessgroups.sh`, not
  run — needs the user's go-ahead), rerun the script for branches, formula
  dual-write, permissions and terminals.
* The stack currently runs the overlaid jar; restore with the stock jar in
  `/root/overlay/rhn.stock.jar` on the VM (or recreate the container).
* Doc drift: `AGENTS_CUSTOM.md` §4 points to `AGENTS_XMLRPC.md`, which does
  not exist in this checkout.
* Known limitations: dual-write overwrites a config shared with other
  groups/children if the same group is later saved through the formula; the
  migration only raises force flags; pillar generation adds ~2 queries per
  group of a minion on every full pillar regeneration.

## Changelog

* 2026-10-19 — implemented and verified (results §5).
* 2026-10-19 — plan written after verification; refinements S1–S5 and the two
  extra config columns recorded.
