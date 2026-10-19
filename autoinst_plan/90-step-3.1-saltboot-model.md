# Step 3.1 Design — Saltboot Deployment Model

> Design for roadmap step 3.1 (`10-roadmap.md` Phase 3): Saltboot deployment
> is modeled in the Java autoinstallation domain instead of group formula
> data. This document records the verified current state, the data model, the
> resolution and merge rules, the migration/transition path, an API sketch,
> and the hand-offs to steps 3.2–3.5. Design only — no code lands in this
> step.

| | |
|---|---|
| Branch | `sles16_agama` |
| Created | 2026-10-19 |
| Status | DONE (design, 2026-10-19) |
| Related | `00-goals.md` §G3/§5.4/§5.7 (amended)/§5.9, `10-roadmap.md` Phase 3, `20-kernel-options.md` §6 |

---

## 1. Current state (verified 2026-10-19)

Paths are relative to the repository root; Java paths relative to
`java/core/src/main/java/`. Line numbers refer to tree `583440e9b18`.

### 1.1 Formulas (external package `saltboot-formula`)

`saltboot-formula` (1.1.0 on the agent VM, Uyuni 2026.08 stack) installs
metadata under `/usr/share/susemanager/formulas/metadata/{saltboot,saltboot-group}/`
and states under `/usr/share/susemanager/formulas/states/{saltboot,saltboot-reactor}/`.
Both formulas are `pillar_only: True`, formula group "Saltboot". Neither form
is in this repository (only a partial `saltboot` fixture:
`susemanager-utils/susemanager-sls/test/data/formulas/metadata/saltboot/form.yml`).

**`saltboot-group`** — pillar category `formula-saltboot-group`, assigned to
*branch* groups (`FormulaFactory.SALTBOOT_GROUP` / `SALTBOOT_PILLAR`,
`com/redhat/rhn/domain/formula/FormulaFactory.java:75-76`). Form root
`saltboot` (`hidden-group`):

| key | type | notes |
|---|---|---|
| `download_server` | text, optional | becomes `MASTER=` on the cobbler group profile |
| `containerized_proxy` | bool, default False | gates visibility of all following fields |
| `default_boot_image` | text | |
| `default_boot_image_version` | text | |
| `default_kernel_parameters` | text | |
| `minion_id_naming` | select FQDN/Hostname/HWType/MAC, default Hostname | |
| `disable_id_prefix` | bool | |
| `disable_unique_suffix` | bool | disabled when naming = HWType |

**`saltboot`** — pillar category `formula-saltboot`, typically on *HWTYPE*
groups, also possible per system. Form root `partitioning`: an `edit-group`
(dictionary of dictionaries) of disks keyed by a symbolic ID (`disk1`,
`md0`, …):

* disk: `type` (DISK|RAID), `device`, `level` / `devices` (RAID),
  `disklabel` (gpt|msdos|none), `partitions`;
* `partitions`: `edit-group` keyed by a symbolic partition ID with
  `size_MiB`, `mountpoint`, `format` (btrfs/ext4/xfs/vfat/swap), `image`,
  `image_version`, `luks_pass`, `flags` (swap/raid/bios_grub/esp/boot);
* for `disklabel: none` the same fields sit directly on the disk.

**Pillar keys consumed by the external states** (the execution engine — must
stay key-compatible): `partitioning`, `images`, `boot_images`,
`saltboot:kernel_action` (state default `kexec`),
`terminal_kernel_parameters`. The branch `saltboot:*` keys are consumed by
Java (cobbler group profile, §1.5). Step 3.2 must grep the installed states
for further `saltboot:` keys before fixing the generator output.

### 1.2 How formula data is merged today

`susemanager-utils/susemanager-sls/modules/pillar/suma_minion.py`:

* `load_group_pillars` (L249) collects formula data **into a dict keyed by
  formula name** — when several groups of a minion carry the same formula,
  the last row read wins (row order undefined); there is no merge across
  groups.
* `load_formula_pillar` (L347) → `merge_formula_data` (L390): system data
  over group data **per form element**. `group`/`hidden-group` elements merge
  field by field; an `edit-group` (e.g. `partitioning`) is handled as a
  primitive — the system value **replaces** the group value as a whole.
  Form defaults are applied (`$default`/`$placeholder`), then
  `adjust_empty_values` (L434).
* Non-formula categories (e.g. `tuning-saltboot`) are recurse-merged into the
  pillar (`salt.utils.dictupdate.merge(strategy="recurse")`).

Consequences for this design: the hierarchy deep merge (§2.3) is **new
semantics**, the multi-group case becomes deterministic (§2.4), and the
migration maps per-system partitioning to a self-contained config (§4.1).

### 1.3 Storage

* `suseSaltPillar` (`schema/spacewalk/common/tables/suseSaltPillar.sql`):
  `id, server_id | group_id | org_id, category, pillar JSONB`, exactly one
  target, unique per target + category.
* Entity `com/redhat/rhn/domain/server/Pillar.java` maps the JSONB column with
  `@JdbcTypeCode(SqlTypes.JSON)` + `columnDefinition = "jsonb"`
  (`Pillar.java:73`) — the pattern to reuse for new JSONB columns.
* **No saltboot-specific tables, no saltboot XML-RPC API.** The only saltboot
  web route is `GET /saltboot/*` (`SaltbootController`, image download
  redirect).

### 1.4 Force-redeploy / force-repartition flags

* Stored in two places: minion pillar category `tuning-saltboot`
  (`saltboot:force_redeploy`, `saltboot:force_repartition`) and org custom-info
  keys `saltboot_force_redeploy` / `saltboot_force_repartition`.
* Both cleared by `SaltbootUtils.resetSaltbootRedeployFlags`
  (`com/suse/manager/saltboot/SaltbootUtils.java:655`), which
  `PXEEventMessageAction` calls unconditionally after every `pxe_update`
  (`com/suse/manager/saltboot/PXEEventMessageAction.java:80`) — also after a
  failed cobbler update.
* The states additionally set grains (`saltboot/force_redeploy.sls` →
  `grains.present saltboot_force_redeploy`).

### 1.5 Images and cobbler entries

* `ImageInfo` (`suseImageInfo`, `pillar_id` FK) / `ImageFile`
  (`suseImageFile`). `SaltStateGeneratorService.generateOSImagePillar`
  (`com/suse/manager/webui/services/SaltStateGeneratorService.java:117`)
  writes pillar `Image<id>` with `images` / `boot_images`; boot image name is
  `name-version-revision`.
* `SaltbootUtils` is the cobbler adapter:
  * distro per image — `createSaltbootDistro` (L192/L214): breed `generic`,
    options `panic=60 splash=silent` (L244-245);
  * image profiles — private `nameVR`, `DEFAULT_IMAGE`, `name`,
    `name-version`, re-pointed to the newest version (`SaltbootVersionCompare`);
  * group profile per branch group — `createSaltbootProfile` (L460/L472/L489)
    via `updateGroupProfile` (L369); options from `getKernelOptions(group)`
    (L404): `MINION_ID_PREFIX=<group name>`, `MASTER=<download_server>`,
    `DISABLE_ID_PREFIX=1`, `DISABLE_UNIQUE_SUFFIX=1`, `USE_FQDN_MINION_ID=1` |
    `DISABLE_HOSTNAME_ID=1` | `USE_MAC_MINION_ID=1`, plus split
    `default_kernel_parameters`; parent image profile from
    `getGroupImageName` (L434, falls back to `DEFAULT_IMAGE`);
  * system record per terminal — `createSaltbootSystem` (L568/L584): group
    profile options + `root=`, `salt_device=`, `terminal_kernel_parameters`;
    resolves cobbler MAC-duplicate conflicts;
  * naming — `makeCobblerName(org, label)` (L105) =
    `label<sep>S<sep>orgId<sep>orgName`.

### 1.6 Triggers and writers

| Site | What happens |
|---|---|
| `FormulaFactory.saveGroupFormulaData` (L214) | saves the formula pillar; for `saltboot-group` calls `SaltbootUtils.createSaltbootProfile(group)` (warn on failure) |
| `FormulaFactory.saveServerFormulaData` (L255) | saves the formula pillar; no saltboot hook |
| `FormulaFactory.saveGroupFormulas` (L430) | on `saltboot-group` deselection **deletes the cobbler group profile** first (L442; `ValidatorException` if systems are attached), then removes the formula pillar |
| `FormulaManager.saveGroupFormulaData` (`com/redhat/rhn/manager/formula/FormulaManager.java:83`) | web UI / XML-RPC entry; delegates to `FormulaFactory`, then `saltApi.refreshPillar` |
| `ImageInspectAction` (`com/redhat/rhn/domain/action/salt/inspect/ImageInspectAction.java:286-289`) | `pxe` image → `generateOSImagePillar` + `createSaltbootDistro` |
| `ImageInfoFactory.delete` (`com/redhat/rhn/domain/image/ImageInfoFactory.java:315-320`) | `deleteSaltbootDistro` |
| `RegisterMinionEventMessageAction` (`com/suse/manager/reactor/messaging/RegisterMinionEventMessageAction.java:643, 721, 743, 799`) | saltboot-initrd registration: branch group from grain `minion_id_prefix`, `TERMINALS`, `HWTYPE:<manufacturer-productname>` groups, then state `saltboot` |
| `PXEEventMessageAction` (event `suse/manager/pxe_update`, `PXEEvent`, routed in `com/suse/manager/reactor/SaltReactor.java:152, 255`) | `createSaltbootSystem` (L66) → terminal boots from disk; failure → `PXEEventFailed` notification (L73); flag reset (L80) |
| `ProxyBackupEventAction.convertRBSToContainerized` (`com/suse/proxy/event/ProxyBackupEventAction.java:194-253`) | assigns `saltboot-group` to the branch group and writes `{containerized_proxy: true, default_boot_image, default_boot_image_version: "", download_server: <fqdn>, minion_id_naming: Hostname}` via `FormulaFactory.saveGroupFormulaData` (L246); `convertSinglePxeEntry` (L284) creates terminal records |
| `UpgradeCommand` (`com/redhat/rhn/manager/satellite/UpgradeCommand.java:60, 63, 103, 191`) | dispatches `rhnTaskQueue` tasks prefixed `upgrade_satellite_`; `MIGRATE_COBBLER` runs `SaltbootMigrationUtils.migrateSaltboot()` in a delayed thread (cobbler auth needs a started tomcat) |

Task rows are queued by SQL upgrade scripts, pattern
`schema/spacewalk/upgrade/susemanager-schema-5.3.1-to-susemanager-schema-5.3.2/520-enable-all-systems-sync-all.sql`
(idempotent `INSERT … WHERE NOT EXISTS`).

### 1.7 Legacy retail branch server path (unsupported, decision D1)

The salt master reactor `/etc/salt/master.d/saltboot.conf` →
`saltboot-reactor/pxe_update.sls` runs `pxe/terminal_entry` on branch servers
targeted by `pxe:branch_id`. It is shipped by the external formula package,
serves only non-containerized branch servers, and is **not modeled**. The
remaining in-repo touchpoint is the RBS → containerized conversion in
`ProxyBackupEventAction` (§1.6).

### 1.8 Supporting facts

* Proxy FQDN: `Server.findPrimaryFqdn()` (`com/redhat/rhn/domain/server/Server.java:1223`,
  `rhnServerFQDN.is_primary`), already used for proxies by
  `ProxyConfigUpdateAcquisitor` (`com/suse/proxy/update/ProxyConfigUpdateAcquisitor.java:65`)
  and in `MinionGeneralPillarGenerator:82`. Proxy check: `Server.isProxy()`
  (`Server.java:1705`).
* Pillar generators: `com/suse/manager/webui/services/pillar/MinionPillarGenerator`
  (`generatePillarData(MinionServer)`, `getCategory()`, `removePillar(MinionServer)`),
  registered in `MinionPillarManager`.
* Notifications: `com/redhat/rhn/domain/notification/types/NotificationType.java`
  (`PXE_EVENT_FAILED` + `PXEEventFailed` is the pattern for new types).

### 1.9 Found on the way (recorded, not fixed here)

* `SaltbootUtils.deleteSaltbootDistro` re-points the `name-version` profile
  with `makeCobblerFilterName` (L329) instead of `makeCobblerFilterNameV`
  (L60) — the `nameV` profile can land on a distro of another version. Likely
  bug; hand to 3.2.
* Redeploy flags are reset even after a failed `pxe_update` (L80) — confirm
  intent in 3.5.

---

## 2. Model

### 2.1 Principles

* **The Java domain becomes the source of truth.** Pillar data turns into
  generated output with the same key shape (`saltboot:*`, `partitioning`), so
  `suma_minion.py` and the external states stay unchanged (guardrail 5).
* The model stores only raw user-typed parameter strings; kernel command-line
  composition goes through the step-3.4 builder (guardrail 1).
* Schema is additive (guardrail 6); the model never references cobbler object
  names (guardrail 4).
* **Containerized proxies only** (D1): no `containerized_proxy` flag.

### 2.2 Two entity families

Branch settings (on branch groups) and partitioning/image configs (on
HWTYPE/rollout groups or systems) sit on **unrelated** groups of the same
terminal. One hierarchical entity with "most-derived wins" would drop one of
them, so the model keeps today's split between `saltboot-group` and `saltboot`:

```
suseSaltbootBranch   1 ── 1  rhnServerGroup (branch)   ──► proxy rhnServer
suseSaltbootConfig   * ── 0..1 parent suseSaltbootConfig
suseSaltbootConfigAssignment  config ──► rhnServerGroup | rhnServer
suseSaltbootTerminal 1 ── 1  rhnServer (terminal)
```

Table names are indicative; step 3.2 finalizes the DDL following
`schema/spacewalk/common/tables/*.sql` conventions (`created` / `modified`,
index files under `schema/spacewalk/postgres/tables/`) plus an idempotent
`schema/spacewalk/upgrade/next/` script. Deliberate deviations from the
legacy conventions (PostgreSQL 18 target): surrogate keys are
`BIGINT GENERATED ALWAYS AS IDENTITY` instead of `NUMERIC` + sequence, and
flags use the native `BOOLEAN` type instead of `CHAR(1)` Y/N.

#### `suseSaltbootBranch` — branch-level settings (replaces `saltboot-group`)

| column | type | notes |
|---|---|---|
| `id` | BIGINT PK, autogenerated identity | |
| `group_id` | FK `rhnServerGroup` ON DELETE CASCADE, **UNIQUE** | the branch group; its name is the `MINION_ID_PREFIX`. Org scoping goes through `rhnServerGroup.org_id` (no own `org_id` column) |
| `proxy_server_id` | FK `rhnServer` ON DELETE SET NULL, nullable | replaces free-text `download_server` (D2). Must be a proxy (`Server.isProxy()`, Java validation). FQDN resolved at generation time via `findPrimaryFqdn()`. |
| `minion_id_naming` | VARCHAR + CHECK (`FQDN`,`Hostname`,`HWType`,`MAC`), default `Hostname` | |
| `disable_id_prefix` | BOOLEAN NOT NULL DEFAULT FALSE | |
| `disable_unique_suffix` | BOOLEAN NOT NULL DEFAULT FALSE | invalid with `HWType` (Java validation, as in the form) |
| `default_boot_image` | VARCHAR, nullable | lax reference (D5): `name`, `name-version` or `name-version-revision`; replaces the formula's `default_boot_image` + `default_boot_image_version` pair |
| `default_kernel_parameters` | VARCHAR, nullable | raw user string |
| `created`, `modified` | TIMESTAMPTZ | |

**FQDN selection extension point.** A parallel effort adds multi-FQDN proxies
(internal/external) with selection flags that are not available yet. Until
then the primary FQDN is used. When that work lands, the selection is added
as one additive column (selector or FK to the proxy-FQDN entity of that work)
and resolved in one place (the branch → FQDN resolver used by the pillar
generator and the 3.4 builder).

#### `suseSaltbootConfig` — hierarchical partitioning/image config (replaces `saltboot`)

| column | type | notes |
|---|---|---|
| `id` | BIGINT PK, autogenerated identity | |
| `org_id` | FK `web_customer` ON DELETE CASCADE | |
| `name` | VARCHAR, UNIQUE (`org_id`, `name`) | |
| `parent_id` | self-FK, nullable, ON DELETE RESTRICT | same-org and cycle checks in Java |
| `partitioning` | JSONB, nullable | shape of the `saltboot` form's `partitioning` value, possibly partial (overrides only). JSON `null` on a disk/partition key is a tombstone. Validated in Java against the form shape (known keys, enums). |
| `kernel_action` | VARCHAR, nullable | maps to `saltboot:kernel_action`; null = inherit |
| `created`, `modified` | TIMESTAMPTZ | |

Image references (`image`, `image_version` inside partitions) stay lax strings
(D5) — no FK to `suseImageInfo`, so rebuilt revisions roll forward without
touching configs.

#### `suseSaltbootConfigAssignment`

| column | type | notes |
|---|---|---|
| `id` | BIGINT PK, autogenerated identity | |
| `config_id` | FK `suseSaltbootConfig` ON DELETE CASCADE | |
| `group_id` | FK `rhnServerGroup` ON DELETE CASCADE, nullable, UNIQUE | |
| `server_id` | FK `rhnServer` ON DELETE CASCADE, nullable, UNIQUE | CHECK exactly one of `group_id` / `server_id` (same idiom as `suseSaltPillar`) |
| `priority` | INTEGER NOT NULL DEFAULT 0 | tie-breaker for unrelated chains only (§2.4) |

At most one config per group and per system.

#### `suseSaltbootTerminal` — per-terminal deployment state

| column | type | notes |
|---|---|---|
| `server_id` | PK, FK `rhnServer` ON DELETE CASCADE | |
| `boot_image` | VARCHAR, nullable | last reported by `pxe_update` |
| `root_device`, `salt_device` | VARCHAR, nullable | from `pxe_update` |
| `terminal_kernel_parameters` | VARCHAR, nullable | from `pxe_update` |
| `force_redeploy`, `force_repartition` | BOOLEAN NOT NULL DEFAULT FALSE | replace the `tuning-saltboot` pillar + custom-info dual storage; the legacy locations are still read and cleared during the transition |
| `last_pxe_update` | TIMESTAMPTZ, nullable | |
| `last_result` | VARCHAR, nullable | status/message of the last `pxe_update` handling, for the future UI |

### 2.3 Effective config: chain merge

The effective config of a config node is the merge of its ancestor chain from
the root to the node itself:

* scalars (`kernel_action`): the nearest non-null value wins;
* `partitioning`: recursive dict merge keyed by the symbolic disk/partition
  IDs (D6) — a child sets only what it changes; a JSON `null` value removes
  the inherited key (tombstone). Non-dict values (strings, numbers, RAID
  `devices` lists) are replaced, not merged.

Reference implementation (Java pseudo-code, DB-free):

```
merge(base, override):
  result = copy(base)
  for (key, value) in override:
    if value == null:                       result.remove(key)      # tombstone
    elif value is Map and result[key] is Map: result[key] = merge(result[key], value)
    else:                                   result[key] = value
  return result
```

Note: this differs from today's formula handling, where an `edit-group` is
replaced as a whole (§1.2). It is new, explicitly chosen behavior.

**A/B rollout example.**

```yaml
# HW-POS-4000 (root, assigned to group HWTYPE:ACME-POS4000)
partitioning:
  disk1:
    type: DISK
    device: /dev/sda
    disklabel: gpt
    partitions:
      p1: {size_MiB: 500, format: vfat, mountpoint: /boot/efi, flags: esp}
      p2: {size_MiB: 20000, format: ext4, mountpoint: /, image: POS_Image_JeOS7, image_version: "7.1.0"}
      p3: {format: ext4, mountpoint: /data}

# rollout-A (parent HW-POS-4000, assigned to group rollout-A): no partitioning → keeps 7.1.0

# rollout-B (parent HW-POS-4000, assigned to group rollout-B)
partitioning:
  disk1:
    partitions:
      p2: {image_version: "7.2.0"}
```

A terminal in `HWTYPE:ACME-POS4000` and `rollout-B` resolves to `rollout-B`
(most-derived, §2.4) and deploys `POS_Image_JeOS7` 7.2.0 with the HW-POS-4000
layout. Moving terminals between the rollout groups switches the image
without touching the layout.

### 2.4 Resolution: which config applies to a terminal (D7)

```
resolve(minion):
  1. if a system assignment exists for minion: winner = its config
  2. else:
       C = configs assigned to any group the minion is a member of
       C = C minus every config that is an ancestor of another config in C
       if |C| == 0: no config (nothing to generate)
       if |C| == 1: winner = the one in C
       else:
         P = configs in C with the highest assignment priority
         if |P| == 1: winner = the one in P
         else: no effective config → notification (new NotificationType,
               pattern PXEEventFailed), no saltboot partitioning pillar for
               the minion → deployment blocked until resolved
  3. effective = merge(chain(winner))      # §2.3
```

A system-level override of a group config is a child config with a system
assignment. The resolver is pure logic (input: assignments + config graph),
unit-testable without a DB.

Branch settings resolve separately: a terminal's branch is the
`suseSaltbootBranch` of its branch group (the group named by the
`minion_id_prefix` grain at registration). More than one branch group per
terminal is a configuration error, reported like the config tie.

### 2.5 Java domain

Package proposal `com.suse.manager.autoinstallation.saltboot` (next to the G0
builders), entities annotated like `Pillar`:

* entities `SaltbootBranch`, `SaltbootConfig`, `SaltbootConfigAssignment`,
  `SaltbootTerminal`; `SaltbootFactory` (HibernateFactory pattern);
* `SaltbootConfigResolver` — §2.3 + §2.4, DB-free;
* `SaltbootPillarGenerator` — a `MinionPillarGenerator` emitting the branch
  `saltboot:*` keys, effective `partitioning`, `saltboot:kernel_action`, and
  the force flags;
* `SaltbootUtils` stays the cobbler adapter but reads branch settings from the
  domain instead of the `formula-saltboot-group` pillar (step 3.2/3.4).

### 2.6 Pillar generation and precedence

* While a saltboot formula still applies to a minion (a `formula-saltboot*`
  pillar on the minion or any of its groups), the formula pillar stays
  authoritative for Salt; dual-write (§4.2) keeps the domain equal to it.
* The domain generator emits **only for minions where no saltboot formula
  applies** — no double merge of formula and domain data.
* Category: a new non-formula category (e.g. `saltboot`). Non-formula
  categories are recurse-merged by `suma_minion.py` without form defaults, so
  the generator emits complete values including the form defaults
  (`minion_id_naming: Hostname`, booleans as `false`). `kernel_action` is
  omitted when unset so the states keep their `kexec` default.

### 2.7 Relation to the future unified profile entity (step 2.1)

A saltboot config is a **deployment kind**, not an uploaded profile file, so
it does not depend on the 2.1 entity. Forward hook: 2.1 may introduce a shared
"autoinstallation deployment" base (type discriminator `saltboot`, …) that
`suseSaltbootConfig` joins later through an additive FK. 3.2 must not add
coupling to `rhnKSData`. For guardrail 3, configs and branches carry name,
type, and target, enough for metadata-driven listing in the later UI.

---

## 3. Decisions (user, planning round 8, 2026-10-19)

| # | Decision | Promoted to |
|---|---|---|
| D0 | Phase order: G3 backend (3.1, 3.2, 3.4, 3.5) proceeds before G1+G2; G3 UI (3.3) waits for the Phase 2 UI foundation (2.2). | `00-goals.md` §5.7 amendment |
| D1 | Only containerized proxies are supported; no `containerized_proxy` flag; the legacy branch-server reactor path is not modeled. | §5.9 |
| D2 | `download_server` becomes an FK to the proxy system; its primary FQDN is used until the parallel multi-FQDN work provides selection flags (extension point §2.2). | §5.9 |
| D3 | Partitioning is stored as JSONB, validated in Java. | §5.9 |
| D4 | Transition by dual-write: formula-save hooks also write the domain; users unassign the saltboot formulas manually when ready. | §5.9 |
| D5 | Boot image references are lax strings (`name`, `name-version`, `name-version-revision`), never an FK. | §5.9 |
| D6 | Config hierarchy with deep merge keyed by disk/partition IDs; JSON `null` tombstones remove inherited entries. | §5.9 |
| D7 | Resolution: system assignment > most-derived config on one chain > assignment priority > notification + blocked deployment. | §5.9 |
| D8 | Migration: upgrade-time Java migration via `UpgradeCommand`, followed by dual-write. | §5.9 |

---

## 4. Migration and transition

### 4.1 Upgrade-time migration (D8)

* New task constant `UpgradeCommand.MIGRATE_SALTBOOT_DOMAIN =
  UPGRADE_TASK_NAME + "migrate_saltboot_domain"` with a `case` calling the
  migration (e.g. `SaltbootDomainMigration.migrate()`). It needs no cobbler
  access, so it can run synchronously inside `upgrade()`.
* Queued by an idempotent `INSERT … WHERE NOT EXISTS` script in
  `schema/spacewalk/upgrade/next/` (pattern: `520-enable-all-systems-sync-all.sql`).
  `upgrade/next/` scripts are re-applied on every server start until release
  (`schema/spacewalk/upgrade/next/README.md`), so the task is re-queued after
  each start — **the migration itself must be idempotent**: skip any group /
  system that already has domain rows.
* Mapping:

  | Source | Target |
  |---|---|
  | group pillar `formula-saltboot-group` | `SaltbootBranch` for that group. `download_server` → proxy whose `rhnServerFQDN.name` matches case-insensitively and `isProxy()`; no match → `proxy_server_id = NULL`, warning log, notification to org admins (proxy must be picked). `default_boot_image` [+ `-` + `default_boot_image_version` if non-empty] → `default_boot_image`. `containerized_proxy` dropped. |
  | group pillar `formula-saltboot` | root `SaltbootConfig` (name = group name, `partitioning` = pillar value) + group assignment (priority 0) |
  | system pillar `formula-saltboot` | **root** `SaltbootConfig` (name = minion id) holding the system's complete `partitioning` + system assignment. Root, not a child: today the system `edit-group` value replaces the group value as a whole (§1.2), so a deep-merged child would change the effective result. |
  | minion pillar `tuning-saltboot` / custom info `saltboot_force_*` | `SaltbootTerminal` flags |

* Formula assignments are **not** removed (D4).
* Multiple groups of one minion carrying `formula-saltboot` (today: last row
  wins, §1.2) migrate to equal-priority assignments → tie (§2.4). The
  migration logs every such minion; the tie only bites once the formulas are
  unassigned, and the admin resolves it with priorities.

### 4.2 Dual-write (D4)

Implemented in `FormulaFactory` — the single funnel for the web UI, XML-RPC
(`FormulaHandler` → `FormulaManager`), and `ProxyBackupEventAction`:

* `saveGroupFormulaData(…, saltboot-group)`: upsert `SaltbootBranch` (same
  mapping as §4.1), then the existing cobbler hook.
* `saveGroupFormulaData(…, saltboot)` / `saveServerFormulaData(…, saltboot)`:
  upsert the group/system config + assignment (same mapping).
* Formula unassignment (`saveGroupFormulas` L430 and the server equivalent):
  **keep the domain rows and do not delete the cobbler group profile** when a
  `SaltbootBranch` exists — today's hook (L442) deletes it and must change in
  3.2. Unassignment is the user's "migrate now" signal; the domain generator
  (§2.6) takes over.
* No reverse write (domain → formula). The new API refuses edits to a
  branch/config while a saltboot formula still applies to its target, with an
  error telling the user to unassign the formula first.

---

## 5. API sketch (implemented in step 3.2)

XML-RPC namespace `saltboot`, handler under
`com.redhat.rhn.frontend.xmlrpc.saltboot`, following `FormulaHandler` /
image handler conventions (`@apidoc` tags, `User loggedInUser` first, org
scoping, `int` 1 on success):

* branches — `createBranch(groupId, proxyId, details)`, `getBranch`,
  `updateBranch`, `deleteBranch`, `listBranches`;
* configs — `createConfig(name, parentId?, partitioning, kernelAction?)`,
  `getConfig`, `getEffectiveConfig(configId)`, `updateConfig`,
  `deleteConfig`, `listConfigs`;
* assignments — `assignConfigToGroup(configId, groupId, priority)`,
  `assignConfigToSystem(configId, sid)`, `unassignConfigFromGroup`,
  `unassignConfigFromSystem`;
* terminals — `getTerminalState(sid)`, `getEffectiveTerminalConfig(sid)`
  (resolution diagnostics incl. tie reporting), `scheduleForceRedeploy(sid,
  repartition)`.

Permission model (org admin vs image admin) is decided in 3.2. No spacecmd
work required (guardrail 5 covers existing behavior only).

---

## 6. Hand-offs

### 6.1 Step 3.2 — domain, schema, API

Schema + entities + factory + resolver + pillar generator + API + upgrade
migration + dual-write hooks; `SaltbootUtils.getKernelOptions` /
`getGroupImageName` read the domain (formula pillar fallback while a formula
applies); the two §1.9 findings. Tests: DB-free resolver/merge tests (A/B,
tombstone, scalar inheritance, priority tie, ancestor elimination, cycle
rejection), DB-backed factory/migration/dual-write tests.

### 6.2 Step 3.4 — kernel options via builders

`SaltbootKernelOptionsBuilder` composing distro (`panic=60 splash=silent`),
branch (`MINION_ID_PREFIX`, `MASTER=<proxy FQDN>`, naming flags, default
parameters), and terminal (`root=`, `salt_device=`, terminal parameters)
option sets from the domain; breed stays `generic`. Saltboot has no
`KickstartableTree` / install type, so the builder is obtained outside the
install-type factory routing (or the factory gains a saltboot entry) —
decided in 3.4.

### 6.3 Step 3.5 — trigger / orchestration

Deployment triggered from the autoinstallation flow on config/branch save and
assignment; formula hooks shrink to dual-write and go away when the formulas
are retired; the `pxe_update` → `PXEEventMessageAction` loop is preserved and
additionally updates `SaltbootTerminal`; force flags move to the domain.

### 6.4 Step 3.3 — React UI

Blocked on step 2.2 (D0).

### 6.5 Follow-ups

* Remove the RBS conversion path (`ProxyBackupEventAction`) once no old
  branch servers remain (D1).
* Multi-FQDN selector once the parallel work lands (D2).

---

## 7. Risks and open items

* **External formula drift** — form and state keys live in `saltboot-formula`
  outside the repository; 3.2 pins the expected key set in fixture-based
  tests.
* **Legacy multi-group ambiguity** — §4.1 tie handling; logged during
  migration.
* **Proxy FQDN mismatch** during migration — branch without proxy +
  notification; the cobbler group profile keeps working from the formula
  pillar until the formula is unassigned.
* **`upgrade/next/` re-application** — covered by migration idempotency.
* **Tombstone semantics** are new — must be documented in the API docs.
* **Deep merge vs today's replace semantics** — intentional (D6); the
  migration preserves today's effective results (§4.1).

## Changelog

* 2026-10-19 — planning round 8: design written; decisions D0–D8 locked by
  the user; current-state facts verified against tree `583440e9b18` and the
  agent VM (`saltboot-formula` 1.1.0). Correction during verification: today's
  formula merge replaces `edit-group` values and does not merge across groups
  (§1.2) — migration mapping adjusted accordingly.
* 2026-10-19 — review: surrogate PKs are `BIGINT` identity columns; flags use
  native `BOOLEAN` instead of `CHAR(1)` Y/N; `suseSaltbootBranch.org_id`
  dropped (org reached through the branch group).
