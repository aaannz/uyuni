# Step 3.4a — Saltboot Distro as Image-Based Tree

> Design for roadmap step 3.4a (`10-roadmap.md` Phase 3): every saltboot boot
> image gets a Java-side distro record — a `KickstartableTree` backed by an
> `ImageInfo` instead of a channel — with a dedicated `saltboot` install type.
> Prerequisite for step 3.4b (saltboot builder routed by install type,
> [`97-step-3.4b-saltboot-kernel-options.md`](97-step-3.4b-saltboot-kernel-options.md))
> and a building block for the Cobbler exit (G6: Java-side record of
> kernel/initrd per bootable distro).

| | |
|---|---|
| Branch | `sles16_agama` |
| Created | 2026-10-19 |
| Status | IN PROGRESS — session 3.4a-1 DONE 2026-10-20, 3.4a-2 open |
| Related | `00-goals.md` §G3/§G5/§G6, §4 guardrails 4–6; `90-step-3.1-saltboot-model.md`; `20-kernel-options.md` §3 |

---

## 1. Current state (verified 2026-10-19, tree `3484a7e3ee9`)

Java paths relative to `java/core/src/main/java/`.

### 1.1 Table and entity

* `rhnKickstartableTree` (`schema/spacewalk/common/tables/rhnKickstartableTree.sql`):
  `channel_id NUMERIC NOT NULL` (FK `rhnChannel` ON DELETE CASCADE),
  `base_path NOT NULL`, `kstree_type NOT NULL` (`rhn-managed` |
  `externally-managed`), `install_type NOT NULL`, `org_id` nullable (null =
  vendor tree), `cobbler_id`, `kernel_options(_post)`; unique index
  `rhn_kstree_oid_label_uq (org_id, label)`; trigger
  `rhn_kstree_mod_trig` (timestamps only — unaffected).
* Dependent tables reference the tree only from kickstart objects:
  `rhnKickstartDefaults.kstree_id`, `rhnKickstartSession.kstree_id`,
  `rhnKSTreeFile.kstree_id`.
* `com/redhat/rhn/domain/kickstart/KickstartableTree.java` (annotations):
  `getDefaultKernelPaths()` / `getDefaultInitrdPaths()` / `getKernelXenPath()`
  derive layout paths from `getChannel().getChannelArch()`;
  `getKernelPath()` / `getInitrdPath()` probe them on disk.

### 1.2 Who sees trees — classification of every lookup

| Lookup (`KickstartFactory`) | Callers | Image trees? |
|---|---|---|
| `lookupKickstartTrees`, `listUnsyncedKickstartTrees` | `CobblerDistroSyncCommand` | **excluded already** (join on channel) |
| `lookupKickstartTreesByChannel*`, `lookupKickstartableTrees`, `getNewestTree`, `verifyTreeAssignment` | profile wizards, `CobblerSyncTask`, `KickstartTreeHandler.list` | **excluded already** (by channel) |
| `lookupKickstartTreeByLabel(label[, org])` | `DownloadFile`, `KickstartInitiateAction`, `BaseTreeAction`, tree ops, `KickstartHandler` ×4, `ProfileHandler`, `KickstartTreeHandler.getDetails` | **leaks** |
| `lookupKickstartTreeByIdAndOrg`, `findTreeById` | ACL handler, tree edit/delete ops, software edit, wizard helper, `KickstartEditCommand` | **leaks** |
| `listTreesByOrg`, `lookupAccessibleTreesByOrg` | `ViewTreesAction` (distribution list), `KickstartLister`, `KickstartWizardHelper` | **leaks** |
| `lookupKickstartTreeByCobblerIdOrXenId` | `CobblerProfileSyncCommand` | leaks only if `cobbler_id` is set (§3.4) |
| `listCandidatesForBacksync` | `CobblerDistroSyncCommand` | leaks only if `cobbler_id` is set |
| `lookupKickstartInstallTypes` | `BaseTreeAction` dropdown, `kickstart.tree.listInstallTypes` | would list `saltboot` |
| `lookupKickstartInstallTypeByLabel` | tree create/edit (UI, XML-RPC) | would accept `saltboot` |

Outside the factory: `ChannelFactory` (by channel, safe), datasource modes in
`General_queries.xml` (all join `rhnChannel` or `rhnKickstartDefaults`, safe).
Python (`reposync.py`, ISS `iss.py`/`dumper.py`/`exportLib.py`/importlib,
`contentRemove.py`, `rhnChannel.py`): every query is by `channel_id` → safe.
`reposync.py` inserts org-owned trees under the `(org_id, label)` uniqueness
that image trees share (T5) — a colliding label fails the insert (risk §6).

### 1.3 Install type

`KickstartInstallType.getCobblerBreed()` returns `redhat` for any label that
is neither `generic_rpm` nor SUSE; `getCobblerOsVersion()` falls back to
`virtio26` for non-redhat/suse breeds. `isGeneric()` = label starts with
`generic` (so `generic_rpm` routes to the RHEL builder and cannot be reused).
`isRhel6OrGreater()`/`isRhel7OrGreater()` are true for any unknown label
(only reachable from kickstart paths).

### 1.4 Image side

* Saltboot distros are created when a Kiwi `pxe` image with a boot image and
  no bundles is inspected (`ImageInspectAction:286-289` →
  `SaltbootUtils.createSaltbootDistro(imageInfo)`) and by the cobbler
  migration task (`SaltbootMigrationUtils.migrateSaltboot` →
  `createSaltbootDistro(imageInfo, distros, con)`).
* Kernel/initrd are `ImageFile` rows (`type` = `kernel` / `initrd`), path =
  `OSImageStoreUtils.getOSImageStorePathForImage(image)` + `ImageFile.file`;
  arch = `ImageInfo.getImageArch()`.
* Deletion: `ImageInfoFactory.delete` → `SaltbootUtils.deleteSaltbootDistro`
  → removes files → removes the `ImageInfo`.

## 2. Decisions

| # | Decision | Status |
|---|---|---|
| T1 | Same entity: `KickstartableTree` gets nullable `channel` and new nullable `image`; **exactly one** is set (CHECK). | user, round 9 |
| T2 | New install type label **`saltboot`** ("Saltboot image"), cobbler breed `generic`; `generic_rpm` is not reused (it routes to the RHEL builder). | user, round 9 |
| T3 | `base_path` = directory holding the image's kernel/initrd; kernel/initrd resolved from the `ImageFile` rows. | user, round 9 |
| T4 | Kickstart-facing lookups **exclude image trees in the factory queries** (`k.image IS NULL`); image trees get their own lookups. No change at the ~30 `getChannel()` call sites. Duplicate-label checks keep seeing **all** trees (one label space). | user, round 9 |
| T5 | Tree labels stay unique per org across **all** trees (existing `rhn_kstree_oid_label_uq` unchanged). The generated tree of an image is labeled `name-version-revision` (unique per org for images). **Several trees per image are allowed** (generated + user-created). | user, round 9 |
| T6 | `cobbler_id` stays **NULL** for image trees in this step: cobbler objects keep being found by the saltboot `:S:` names in `SaltbootUtils`; no cobbler sync/backsync path ever sees image trees (guardrail 4). | user, round 9 |
| T7 | Users may create **their own image-based trees**: install type `saltboot` + image id instead of channel + tree path. The legacy JSP distribution UI hides/rejects `saltboot` until the React UI (Phase 2 / 3.3) lands; existing XML-RPC `kickstart.tree.create/update` reject `saltboot` (`listInstallTypes` output unchanged — guardrail 5); **new XML-RPC calls** manage image trees with an image id parameter (§3.4). | user, round 9 |

## 3. Design

### 3.1 Schema (additive, `schema/spacewalk/upgrade/next/` + source files)

```sql
ALTER TABLE rhnKickstartableTree ALTER COLUMN channel_id DROP NOT NULL;
ALTER TABLE rhnKickstartableTree ADD COLUMN IF NOT EXISTS image_id NUMERIC
    CONSTRAINT rhn_kstree_iid_fk REFERENCES suseImageInfo (id) ON DELETE CASCADE;
ALTER TABLE rhnKickstartableTree ADD CONSTRAINT rhn_kstree_source_ck
    CHECK (num_nonnulls(channel_id, image_id) = 1);          -- guarded for re-runs
CREATE INDEX IF NOT EXISTS rhn_kstree_iid_idx ON rhnKickstartableTree (image_id);
-- rhn_kstree_oid_label_uq (org_id, label) stays as is (T5)
INSERT INTO rhnKSInstallType (id, label, name)
    SELECT sequence_nextval('rhn_ksinstalltype_id_seq'), 'saltboot', 'Saltboot image'
    WHERE NOT EXISTS (SELECT 1 FROM rhnKSInstallType WHERE label = 'saltboot');
```

* Source updates: `tables/rhnKickstartableTree.sql` (column, check, indexes),
  `tables.deps` (`rhnKickstartableTree` now depends on `suseImageInfo` —
  verify no dependency cycle in blend ordering), `data/rhnKSInstallType.sql`.
* Every statement idempotent (`upgrade/next` re-applies on each start until
  release, `90-…` §4.1); the CHECK is added via a `DO` block checking
  `pg_constraint`.
* `suseImageInfo.id` FK target type: verify (`NUMERIC` vs `BIGINT`) and match.
* No reportdb impact (no tree table in `schema/reportdb`) — verify.

### 3.2 Domain

* `KickstartInstallType`: `SALTBOOT = "saltboot"`, `isSaltboot()`;
  `getCobblerBreed()` → `generic` for saltboot (explicit branch next to
  `generic_rpm`). RHEL predicates left as they are (§1.3, unreachable).
* `KickstartableTree`: `@ManyToOne(fetch = LAZY) @JoinColumn(name =
  "image_id") ImageInfo image` + getter/setter; `isImageTree()`.
  For image trees: `getKernelPath()` / `getInitrdPath()` return
  `getAbsolutePath()`-based paths of the `kernel`/`initrd` `ImageFile`
  (no layout probing, no channel access); `getDefaultKernelPaths()` /
  `getDefaultInitrdPaths()` return those single paths; `getKernelXenPath()`
  / `getInitrdXenPath()` return null. Arch helper `getArchLabel()`-style
  accessors are **not** added unless a 3.4a caller needs them (scope rule).
* `KickstartFactory`:
  * add `AND k.image IS NULL` to `lookupKickstartTreeByLabel` (both),
    `lookupKickstartTreeByIdAndOrg`, `findTreeById`, `listTreesByOrg`,
    `lookupAccessibleTreesByOrg`, `lookupKickstartTreeByCobblerIdOrXenId`,
    `listCandidatesForBacksync` (the last two defensive, T6);
  * `lookupKickstartInstallTypes()` excludes `saltboot` (T7);
  * image-tree lookups: `lookupImageTreeByLabel(label, org)`,
    `listImageTrees(ImageInfo)`, `listImageTreesByOrg(org)`;
  * unfiltered `treeLabelExists(label, org)` used by every duplicate-label
    check (`TreeCreateOperation`, the UI/XML-RPC rename paths, the new image
    tree calls) — one label space (T4/T5); exact call sites listed at
    implementation start.
* T7 rejection: `BaseTreeAction#processCommandSetters` (step-1.8 guard
  already returns `kickstart.tree.invalidinstalltype` for unresolved types —
  extend to `isSaltboot()`), XML-RPC `KickstartTreeHandler.create/update`
  (same `InvalidKickstartTreeException`-style fault as an unknown type).
* `KernelOptionsBuilderFactory`: **no change in 3.4a** — `saltboot` routing
  lands in 3.4b with the builder. Until then `getBuilder(saltboot)` throws
  `KernelOptionsBuilderException` (unknown-type rule), and no 3.4a path calls
  it for image trees.

### 3.3 Lifecycle

Two kinds of image trees, no flag column: the **generated** tree of an image
is the one whose label equals the image's generated label
(`name-version-revision`); every other image tree is **user-created**.

* **Generated — create:** `SaltbootUtils.createSaltbootDistro(imageInfo,
  distros, con)` first ensures the generated tree (before its early return
  for an existing distro), so both callers — inspect and the cobbler
  migration — create it: label = `name-version-revision`, org = image org,
  `install_type` = `saltboot`, `kstree_type` = `externally-managed`,
  `base_path` = parent dir of the kernel/initrd paths (T3; error if they
  differ), `image` = imageInfo. Missing kernel/initrd → existing
  `SaltbootException`, no tree. If the label is taken by another tree
  (§6), is longer than 64 characters or does not match the image-tree label
  rule `^[-_.0-9A-Za-z]{1,64}$` (O1/O2, §5a.2), the tree is skipped with a
  warning — the cobbler distro is still created as today. The same rule
  validates user-created image tree labels (§3.4).
* **Generated — delete:** only together with its image. The new delete call
  refuses it (it mirrors the cobbler distro, which lives as long as the
  image).
* **User-created:** created/updated/deleted through the new API (§3.4) —
  pure data in this step (T6: no cobbler object; consumers arrive with 3.4b
  / the trigger model 3.5 / G6). Optional `kernel_options(_post)` are stored
  as typed.
* **Image deletion:** `ImageInfoFactory.delete` removes all trees of the
  image explicitly (keeps the Hibernate session consistent) before removing
  the `ImageInfo`; the FK cascade is the safety net. Image trees are never
  referenced by kickstart profiles/sessions (the filtered lookups never hand
  them out), so no `rhnKickstartDefaults` FK blocks the delete.
* **Migration of existing images:** new `UpgradeCommand` task
  `upgrade_satellite_create_saltboot_trees` queued idempotently from
  `upgrade/next`; it ensures the generated tree for every Kiwi image that has
  both `kernel` and `initrd` files and no `bundle` file (the inspect
  condition). No cobbler access needed (T6) → runs synchronously in
  `upgrade()`, like `MIGRATE_SALTBOOT_DOMAIN`.

### 3.4 XML-RPC: image trees (T7)

New calls in the `kickstart.tree` namespace (handler
`KickstartTreeHandler`, `@apidoc` like the existing calls), all org-scoped,
same permission as the existing tree calls (verify at implementation start):

| Call | Notes |
|---|---|
| `createFromImage(treeLabel, imageId, installType, kernelOptions, kernelOptionsPost)` | `installType` must be an image-based type — only `saltboot` today (keeps the call usable for G5 image types, e.g. combustion, without a new signature); image must belong to the org and have `kernel` + `initrd` files; duplicate label → existing duplicate-label fault. Overload without the option strings. |
| `listImageTrees()` / `listImageTrees(imageId)` | label, image id, install type, kernel options |
| `getImageTreeDetails(treeLabel)` | new serializer for image trees (the existing `KickstartTreeSerializer` dereferences the channel and stays untouched) |
| `updateImageTree(treeLabel, kernelOptions, kernelOptionsPost)` | options only; image and type are fixed |
| `deleteImageTree(treeLabel)` | refuses the generated tree (§3.3) |

* Existing `create`/`update` reject `saltboot` with the unknown-install-type
  fault; existing `getDetails`/`delete`/`rename`/`list*` never see image
  trees (T4) — their behavior and output are unchanged (guardrail 5).
* New endpoints need RBAC rows (`schema/spacewalk/common/data/endpoint.sql`,
  `endpointNamespace.sql` + an `upgrade/next` script), following step 3.2's
  `020-saltboot-api-endpoints.sql`.
* Image trees stay invisible in the JSP UI (distribution list filtered by
  T4, install type hidden by T7) until the React UI.

### 3.5 Not in this step

* Saltboot builder and factory routing, computed distro options merged into
  `kernel_options` — 3.4b.
* Cobbler objects for user-created trees, pointing cobbler objects at trees
  (`cobbler_id`), deriving cobbler names from trees — G6 seam (step 5.1).
* Selecting a tree from a branch/config (which tree a terminal boots) —
  trigger model 3.5 / UI 3.3.
* UI for image trees — 3.3 / Phase 2 UI.

## 4. Tests

* **DB-free:** `KickstartInstallType` saltboot predicate + breed/os-version;
  `KickstartableTree` image kernel/initrd path resolution (mocked
  `ImageInfo`/`ImageFile`).
* **DB-backed:** upgrade script applied twice; CHECK rejects none/both
  sources; one label space (kickstart tree create/rename rejects a label
  used by an image tree with the normal duplicate fault, and vice versa);
  every filtered lookup ignores an image tree (label, id, org lists, cobbler
  id, backsync); install type hidden from the list and rejected by tree
  create/update (UI action + XML-RPC); generated tree on inspect /
  `createSaltbootDistro` (incl. existing-distro early return, label
  collision skip); several trees per image; all trees deleted with the
  image; migration idempotency; new XML-RPC calls (`KickstartTreeHandlerTest`:
  create incl. foreign-org image / image without kernel / non-image install
  type / duplicate label, list, details, update, delete incl. generated-tree
  refusal) + RBAC rows; regression: tree-op suites,
  `KickstartTreeHandlerTest`, `CobblerDistroSyncCommand`/`CobblerSyncTask`
  tests, saltboot suites (`SaltbootUtilsTest`, `SaltbootMigrationUtilsTest`,
  `PXEEventTest`), `ImageInfoFactoryTest`, `ImageInspectActionTest` (if
  present).
* Checkstyle main + tests.

## 5. Sessions

1. **3.4a-1** — schema + upgrade script, install type, entity, factory
   filters/lookups + unfiltered label check, T7 guards on the existing
   UI/XML-RPC, DB-free + DB-backed tests.
2. **3.4a-2** — lifecycle (generated tree on create, deletion with the
   image), upgrade migration task, new image-tree XML-RPC calls + RBAC rows,
   E2E on the agent VM: upgrade on a stack with existing pxe images
   (generated trees created, distribution list and existing XML-RPC output
   unchanged), image-tree API round trip.
3. **3.4b** — builder work (`97-…`), routed by the `saltboot` install type.

## 5a. Session 3.4a-1 — implementation plan

Verified against tree `3484a7e3ee9` (2026-10-19). Java paths relative to
`java/core/src/`.

### 5a.1 Facts checked for this session

* `suseImageInfo.id` is `NUMERIC` → `image_id NUMERIC` matches.
  `tables.deps`: `rhnKickstartableTree :: rhnChannel rhnKSTreeType
  web_customer …`; `suseImageInfo` depends on `rhnServerAction
  suseImageProfile suseImageStore suseMinionInfo …` — none of these depend on
  `rhnKickstartableTree`, so adding `suseImageInfo` is cycle-free (confirmed
  by the blend build, §5a.4).
* `upgrade/next/` holds `001-…`, `010-…`, `020-…` → new script
  `030-saltboot-image-tree.sql`.
* Duplicate-label checks today: `TreeCreateOperation#store` and
  `BaseTreeAction#processCommandSetters` (label change in the UI), both via
  `lookupKickstartTreeByLabel`. XML-RPC `create` goes through
  `TreeCreateOperation`. XML-RPC `rename` has **no** duplicate check (a clash
  ends in the DB constraint) — pre-existing, left as is.
* Install-type resolution from user input: only
  `BaseTreeAction#processCommandSetters` (step-1.8 guard) and
  `KickstartTreeHandler#getInstallType` (used by `create` and `update`,
  throws `NoSuchKickstartInstallTypeException`).
* `lookupKickstartInstallTypes()` is a cacheable HQL list — callers:
  `BaseTreeAction` dropdown, `kickstart.tree.listInstallTypes`.
* Kickstart label rule `VALIDATE_LABEL_REGEX = ^[-_0-9A-Za-z]{1,255}$`
  (no dots); column `label VARCHAR(64)`; `suseImageInfo.name` and `version`
  are `VARCHAR(128)` each → see open items O1/O2.
* Test homes: `KickstartFactoryTest` (`domain/kickstart/factory`),
  `KickstartableTreeTest`, `KickstartInstallTypeTest`, tree-op suites
  (`manager/kickstart/tree`), `BaseTreeActionTest`, `KickstartTreeHandlerTest`;
  image fixtures via `ImageTestUtils.createImageInfo(…)` +
  `createImageFile(image, filename, type)`.

### 5a.2 Open items — decided (user, 2026-10-19): **O1 (a), O2 (a)**

Label column stays `VARCHAR(64)` for now; a generated label longer than 64
skips the generated tree with a warning. Image trees use their own label rule
`^[-_.0-9A-Za-z]{1,64}$` (dots allowed); the generated label is the exact
`name-version-revision`. Both are implemented in 3.4a-2 (image-tree create
path); 3.4a-1 is unaffected.

* **O1 — label length.** Generated labels `name-version-revision` can exceed
  `VARCHAR(64)`. Options: (a) keep 64, skip the generated tree with a warning
  when too long (typical `POS_Image_JeOS7-7.1.0-3` is ~25 chars); (b) widen
  `label` to 256 in this session's script (also matches the 255 in the regex;
  check ISS export/import and UI field lengths). Recommendation: (a), as long
  as no real-world image names exceed it.
* **O2 — dots in labels.** Image versions contain dots, the kickstart label
  rule forbids them. Options: (a) image trees get their own rule
  `^[-_.0-9A-Za-z]{1,64}$` (no DB constraint exists; image trees are never
  served under `/ks/dist/<label>`), generated label = exact
  `name-version-revision`; (b) sanitize dots to `_` in the generated label
  and keep one rule. Recommendation: (a) — the label stays the recognizable
  image identity and matches the saltboot cobbler names. Affects 3.4a-2 code
  only (the rule lives with the image-tree create path), decided now.

### 5a.3 Work items

1. **Schema**
   * `schema/spacewalk/common/tables/rhnKickstartableTree.sql`: `channel_id`
     nullable; `image_id NUMERIC` + FK `rhn_kstree_iid_fk` → `suseImageInfo`
     ON DELETE CASCADE; CHECK `rhn_kstree_source_ck
     (num_nonnulls(channel_id, image_id) = 1)`; index `rhn_kstree_iid_idx`.
   * `tables.deps`: add `suseImageInfo` to `rhnKickstartableTree`.
   * `schema/spacewalk/common/data/rhnKSInstallType.sql`: `saltboot` /
     `Saltboot image`.
   * `schema/spacewalk/upgrade/next/030-saltboot-image-tree.sql`: §3.1,
     every statement idempotent (CHECK via `DO` block on `pg_constraint`).
   * `susemanager-schema.changes.oholecek.saltboot_image_tree`.
2. **Install type** (`main/java/com/redhat/rhn/domain/kickstart/KickstartInstallType.java`):
   `SALTBOOT` constant, `isSaltboot()`, `getCobblerBreed()` → `generic`.
3. **Entity** (`KickstartableTree.java`): `image` (`@ManyToOne(fetch = LAZY)
   @JoinColumn(name = "image_id")`), getter/setter, `isImageTree()`; image
   branch in `getDefaultKernelPaths()` / `getDefaultInitrdPaths()` (single
   path of the `kernel` / `initrd` `ImageFile`, same expression as
   `createSaltbootDistro`: store path for the image + `ImageFile.file`) so
   `getKernelPath()` / `getInitrdPath()` work unchanged; `getKernelXenPath()`
   / `getInitrdXenPath()` return null for image trees. Missing file →
   `getDefault*Paths()` returns an empty array → existing
   `INVALID_KERNEL` / `INVALID_INITRD` validator error.
4. **Factory** (`KickstartFactory.java`):
   * `AND k.image IS NULL` in `lookupKickstartTreeByLabel` (both),
     `lookupKickstartTreeByIdAndOrg`, `findTreeById`, `listTreesByOrg`,
     `lookupAccessibleTreesByOrg`, `lookupKickstartTreeByCobblerIdOrXenId`,
     `listCandidatesForBacksync` (criteria `isNull(root.get("image"))`);
   * `lookupKickstartInstallTypes()` → `WHERE t.label <> 'saltboot'`;
   * new `treeLabelExists(String label, Org org)` (unfiltered),
     `lookupImageTreeByLabel(label, org)`, `listImageTrees(ImageInfo)`,
     `listImageTreesByOrg(Org)`.
5. **Duplicate-label checks** → `treeLabelExists` in `TreeCreateOperation#store`
   and `BaseTreeAction#processCommandSetters` (error keys unchanged:
   `distribution.tree.exists`).
6. **T7 guards on existing paths:** `BaseTreeAction#processCommandSetters`
   treats `saltboot` like an unresolved type (`kickstart.tree.invalidinstalltype`);
   `KickstartTreeHandler#getInstallType` throws
   `NoSuchKickstartInstallTypeException` for `saltboot`.
7. `spacewalk-java.changes.oholecek.saltboot_image_tree`.

Not in this session: lifecycle hooks, migration task, new XML-RPC calls,
label rule for image trees (3.4a-2).

### 5a.4 Tests and verification

* **DB-free:** `KickstartInstallTypeTest` — `isSaltboot()`, breed `generic`,
  os version `virtio26`, saltboot is neither SUSE nor generic (factory
  precedence stays safe).
* **DB-backed** (agent VM, pgsql-4eclipse container with the branch schema,
  `AGENTS_CUSTOM.md` §3):
  * `KickstartableTreeTest` — image tree persists with `channel` null;
    kernel/initrd paths from `ImageFile`; missing kernel → validator error;
    CHECK rejects a tree with both or neither source (flush → constraint
    violation).
  * `KickstartFactoryTest` — each filtered lookup ignores an image tree with
    the same label/org/cobbler id as the query; install-type list lacks
    `saltboot`; `treeLabelExists` sees both kinds; image-tree lookups;
    image deletion cascades the tree at DB level.
  * `TreeCreateOperationTest` / `BaseTreeActionTest` — kickstart tree with an
    image-tree label → `distribution.tree.exists`; `saltboot` install type →
    `kickstart.tree.invalidinstalltype`.
  * `KickstartTreeHandlerTest` — `create`/`update` with `saltboot` →
    `NoSuchKickstartInstallTypeException`; `listInstallTypes` unchanged;
    `getDetails`/`delete`/`rename` on an image-tree label → not found.
  * Regression: tree-op suites, `BaseTreeEditOperationKernelOptionsTest`,
    `CobblerDistroSyncCommand`/`CobblerSyncTask` tests, saltboot suites,
    builder/factory/list suites (`20-kernel-options.md` §9.3).
* Schema: `make -C schema/spacewalk/postgres main` (blend order, no cycle; do
  not commit `main.sql`), `030-…` applied twice on the test DB.
* `mvn -f java compile`, checkstyle main + tests (command in `95-…` §5).

## 6. Risks

* **Unknown leak sites** — any future HQL over `KickstartableTree` that does
  not go through the filtered factory methods sees image trees; mitigated by
  the review list in §1.2 and a test per filtered lookup; reconsider a
  Hibernate-level split (`@SQLRestriction`) if leaks accumulate.
* **Shared label space** — a later reposync of a custom (org-owned) tree
  whose label equals an image tree label fails on the unique index (vendor
  trees have `org_id` NULL and never collide). Image labels
  (`name-version-revision`) and distro labels rarely overlap; accepted, and
  the generated-tree path skips instead of failing (§3.3).
* **blend dependency order** — `rhnKickstartableTree` → `suseImageInfo`;
  check `tables.deps` for cycles before relying on it.

## Changelog

* 2026-10-19 — planning round 9: step 3.4 split into 3.4a (this) and 3.4b;
  survey of tree lookups/Python consumers/install type done; T1–T3 by the
  user, T4–T7 proposed.
* 2026-10-19 — review: T4/T6 acked; T5 revised (one label space across all
  trees, no per-image uniqueness, generated label `name-version-revision`);
  T7 revised (user-created image trees; JSP hides `saltboot`, existing
  XML-RPC rejects it, new `kickstart.tree` image-tree calls with an image id
  parameter, §3.4); unfiltered duplicate-label check added. All T decisions
  now locked.
* 2026-10-19 — session 3.4a-1 planned (§5a): facts verified (FK type, deps,
  duplicate-label and install-type entry points, test homes); open items O1
  (label length) and O2 (dots in image-tree labels) raised.
* 2026-10-19 — O1 (a) keep 64 chars, skip too-long generated labels; O2 (a)
  image trees allow dots (own label rule). Session 3.4a-1 ready to start.
* 2026-10-20 — session 3.4a-1 implemented as planned in §5a.3 (items 1–7).
  Deviations/notes: `treeLabelExists` also matches vendor trees
  (`org IS NULL`) to keep the old duplicate-check semantics;
  `findTreeById` returns null for image trees explicitly;
  `KickstartableTree#pathExists` is null-safe (image trees have no Xen
  paths). Tests: new `KickstartImageTreeTest` (10), additions in
  `KickstartInstallTypeTest`, `TreeCreateOperationTest`, `BaseTreeActionTest`,
  `KickstartTreeHandlerTest`, fixtures `KickstartTestUtils#createSaltbootImage`
  / `#createImageTree`. Agent VM (`mgradm stop` → pgsql-4eclipse container
  with the branch schema, stack restored afterwards): 65 + 179 tests green;
  upgrade script applied twice and replayed on a rebuilt pre-change schema.
  VM caveat: its `~/.m2` is stale against the branch poms (jackson 2.18.9 vs
  2.18.11, branding 5.3.1 vs 5.3.2, older servlet API) and central is
  blocked — verification used VM-copy-only pins of those versions and two
  `@Override` removals in unrelated test mocks (reverted; nothing committed).
  Local sandbox cannot run Maven (exec/jar plugins not cached).
