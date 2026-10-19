# Step 1.1 Design — Kernel Options Builder Hierarchy

> Implementation design for roadmap step 1.1 (`10-roadmap.md` Phase 1). The
> behavioural specification remains [`20-kernel-options.md`](20-kernel-options.md);
> this document fixes the Java design of the builder hierarchy and records the
> planning-round-2 decisions that deviate from the round-1 spec wording.

| | |
|---|---|
| Branch | `sles16_agama` |
| Created | 2026-10-08 |
| Status | DONE (2026-10-08) |
| Related | `00-goals.md` §G0/§5.1, `10-roadmap.md` step 1.1, `20-kernel-options.md` §2–§7 |

---

## 1. Decisions locked in this round

These refine (and where noted, supersede) the round-1 wording in
`20-kernel-options.md`; the spec doc is updated in the same commit.

1. **Install type is the single routing key.** No breed-based dispatch and no
   breed-based fallback (`getBuilderForBreed` is not built). Every routing
   decision goes through `KickstartInstallType` predicates, so routing can
   never dead-end in unreachable branches.
2. **Unknown install type → exception.** The factory's `else` branch throws
   `KernelOptionsBuilderException` instead of silently defaulting.
3. **No Debian builder.** `rhnKSInstallType` contains no debian/ubuntu type
   (labels: `fedora18, generic_rpm, opensuse16.1, rhel_6..10, sles10..15generic,
   suse`), and Uyuni-created distros cannot carry a debian breed — a Debian
   builder would be unroutable dead code. The initiate path's dead
   debian/ubuntu branch is removed when step 1.4 migrates it. Real Debian
   support remains from-scratch future work (`00-goals.md` §5.8).
4. **`profileOptions` is empty until the Cobbler exit (G6).** Cobbler's
   `tftpgen` owns the installer-selection option on generated PXE entries
   (AutoYaST denylist → `autoyast=`, everything else incl. `opensuse16*` →
   `inst.auto=`, redhat → `ks=`); Uyuni must not duplicate it on cobbler
   profile records (`20-kernel-options.md` §6). The method stays in the
   builder contract with an empty default in the abstract base — enabling it
   post-Cobbler is a one-line override, not a call-site sweep.
5. **rhel6 compatibility dropped.** `kssendmac` is never emitted; RHEL
   system options are always `inst.ks.sendmac` + `ks=<url>` (also removes the
   initiate path's rhel6 branch in step 1.4).
6. **`selfUpdateOption` returns `KernelOptionsList`** (zero or one option) so
   the system path can merge it with `addMissingOptions` like every other
   builder output.
7. **Package layout:** interface, base, builders and exception in
   `com.suse.manager.autoinstallation.builder`; factory in
   `com.suse.manager.autoinstallation.installer`.

## 2. Package and file layout

```
java/core/src/main/java/com/suse/manager/autoinstallation/
├── KernelOptionsList.java                          (exists)
├── builder/
│   ├── KernelOptionsBuilder.java                   (interface)
│   ├── AbstractKernelOptionsBuilder.java           (shared logic)
│   ├── KernelOptionsBuilderException.java
│   ├── RhelKernelOptionsBuilder.java
│   ├── AutoYastKernelOptionsBuilder.java
│   └── AgamaKernelOptionsBuilder.java
└── installer/
    └── KernelOptionsBuilderFactory.java
```

Tests mirror this under `java/core/src/test/java/com/suse/manager/autoinstallation/`.

## 3. Builder contract

All methods return a **fresh, computed** `KernelOptionsList`. They never read
or merge the caller's existing options — user-wins merging is the call site's
job via `KernelOptionsList#addMissingOptions` (§5.1 of the spec). This keeps
builders pure and trivially testable.

| Method | Produces | Notes |
|---|---|---|
| `distroOptions(KickstartableTree)` | Boot-the-distro options; persisted into `tree.kernelOptions(_post)` and recomputed on tree create/edit | see §5 |
| `selfUpdateOption(KickstartableTree)` | Only the installer-updates option (`self_update=` / `inst.self_update=`), or empty for RHEL | reused by the system path |
| `profileOptions(Profile)` | **Empty until G6** (decision 4) | flip-switch seam |
| `systemOptions(SystemRecord)` | Per-system options incl. the installer-selection option (`ks=`/`autoyast=`/`inst.auto=`) | used only where Uyuni composes the whole boot line: the initiate path — never written to cobbler records (§6 of spec) |

`networkBoot`/`localBoot` full-line compositions from the original round-1
design were dropped before implementation (2026-10-08 review): every Phase 1
call site either persists per-record options or composes from the *resolved*
cobbler options map, so nothing composes a fresh boot line from builder
parts — they would have been dead stubs. Step 1.4 adds exactly what it needs
(the `text`→`textmode` helper and the nopxe URL) when it migrates.

## 4. Factory routing

`installer/KernelOptionsBuilderFactory`:

```
getBuilder(KickstartInstallType installType, String serverFqdn)
getBuilder(KickstartInstallType installType, String serverFqdn, User user)

  installType.isSLES16OrGreater() → AgamaKernelOptionsBuilder     # BEFORE SUSE:
  installType.isSUSE()            → AutoYastKernelOptionsBuilder  # opensuse16.1 isSUSE()==true
  installType.isRhel() || isFedora() || isGeneric()
                                  → RhelKernelOptionsBuilder
  else                            → throw KernelOptionsBuilderException
```

* `serverFqdn` is the **kickstart host** (tree path: canonical local hostname
  via `InetAddress#getByName(...).getCanonicalHostName()` as today; initiate
  path: the resolved kickstart host). Constructors keep it immutable.
* Optional `User` context only affects the installer-updates child lookup (§7).
* `isSLES16OrGreater()` = `isSLES16() || (isSLES15OrGreater() && !isSLES15())`
  (`KickstartInstallType.java:199`) — checked first so `opensuse16.1` never
  falls through to the AutoYaST builder.

## 5. Per-builder behaviour

Option strings reproduce the legacy forms exactly (`http://` scheme
everywhere, `20-kernel-options.md` §4):

### 5.1 `RhelKernelOptionsBuilder`

* `distroOptions`: `inst.repo=http://<fqdn>/ks/dist/<treeLabel>`
  unconditionally — the Anaconda `inst.*` options exist since RHEL 7, the
  minimal supported RHEL version (legacy code gated on `isRhel8OrGreater()`;
  gate dropped).
* `selfUpdateOption`: empty (RHEL has no installer-updates channel concept).
* `profileOptions`: inherited empty default.
* `systemOptions`: `ks=http://<fqdn>/cblr/svc/op/autoinstall/system/<systemName>`
  + `inst.ks.sendmac` (no rhel6 special case, decision 5).

### 5.2 `AutoYastKernelOptionsBuilder` (SLES 10–15, generic `suse` label)

* `distroOptions`: `install=http://<fqdn>/ks/dist/<treeLabel>` +
  `self_update=<installerUpdatesUrl|0>`.
* `selfUpdateOption`: `self_update=<installerUpdatesUrl|0>`.
* `profileOptions`: inherited empty default.
* `systemOptions`: `autoyast=http://<fqdn>/cblr/svc/op/autoinstall/system/<systemName>`.

### 5.3 `AgamaKernelOptionsBuilder` (SLE16+, `opensuse16*`)

* `distroOptions`:
  `root=live:http://<fqdn>/ks/dist/<treeLabel>/LiveOS/squashfs.img` +
  `inst.self_update=<installerUpdatesUrl|0>` — **no `install=`** (committed
  behaviour, `00-goals.md` §5.1).
* `selfUpdateOption`: `inst.self_update=<installerUpdatesUrl|0>`.
* `profileOptions`: inherited empty default.
* `systemOptions`: `inst.auto=http://<fqdn>/cblr/svc/op/autoinstall/system/<systemName>`.

`<installerUpdatesUrl|0>` = the installer-updates child URL
`http://<fqdn>/ks/dist/child/<childLabel>/<treeLabel>` when the tree's channel
has such a child, else the literal `0` (self-update disabled).

## 6. Abstract base responsibilities

`AbstractKernelOptionsBuilder`:

* Immutable `serverFqdn` + optional `User` (constructor-injected).
* URL helpers: dist tree, installer-updates child, cobbler
  autoinstall/nopxe service URLs (§4 catalogue of the spec).
* Installer-updates child resolution (single home for the §5.5 semantics):
  `User` set → `channel.getAccessibleChildrenFor(user)` (user-scoped, legacy
  distro/system-path behaviour); `User` absent →
  `ChannelFactory.listAllChildrenForChannel` (org-global) — both filtered by
  `Channel::isInstallerUpdates`, first match wins, else `0`.

The shared SUSE `text`→`textmode` normalisation helper is **not** part of
this step: it has no caller until the initiate path migrates in step 1.4,
where it is added (single implementation, SUSE-family builders only) —
avoiding dead code per the working practices.

## 7. Installer-updates child lookup (legacy semantics, §5.5 of spec)

| Context | User context | Lookup |
|---|---|---|
| Distro path (`TreeCreateOperation`, step 1.2) | set | user-scoped |
| System path (`CobblerSystemCreateCommand`, step 1.3) | set | user-scoped |
| Any future headless caller (taskomatic, API) | absent | org-global |

## 8. Out of scope for step 1.1

* Any call-site wiring (steps 1.2–1.5) — this step is a pure addition.
* Enabling `profileOptions` (G6 flip-switch, decision 4).
* Initiate-path migration incl. removal of its dead debian/ubuntu branch
  (step 1.4, decisions 3 and 5).
* Breed-based fallback — not built at all (decision 1).

## 9. Test plan (DB-free, runs without the PostgreSQL container)

Plain JUnit 5 + Mockito (both available via the parent pom; style follows the
existing `KernelOptionsListTest` — no DB base classes):

* `RhelKernelOptionsBuilderTest` / `AutoYastKernelOptionsBuilderTest` /
  `AgamaKernelOptionsBuilderTest` — mocked `KickstartableTree`, `Profile`,
  `SystemRecord`, `Channel` (jMock `MockObjectTestCase` +
  `ByteBuddyClassImposteriser`, the established DB-free pattern):
  * exact option strings per §5 (incl. `0` fallback when no installer-updates
    child);
  * user-scoped child lookup and skipping of non-installer-updates children.
    The org-global fallback is not testable DB-free (static
    `ChannelFactory` call) — it is covered by the DB-backed call-site tests
    in step 1.6/CI;
  * `profileOptions` empty for every builder (decision 4);
  * null inputs rejected with `KernelOptionsBuilderException`.
* `KernelOptionsBuilderFactoryTest`:
  * `opensuse16.1` → Agama (SLE16-before-SUSE precedence);
  * `sles15generic`/`suse` → AutoYaST;
  * `rhel_*`/`fedora18`/`generic_rpm` → RHEL;
  * unknown/other label → `KernelOptionsBuilderException`.
* Idempotency: applying `addMissingOptions(existing, builder.distroOptions(tree))`
  twice yields an unchanged list (guards the step 1.2 merge contract).

## 10. Verification

```
mvn -f java/core compile
mvn -f java/core test -DskipTests=false -Dtest='KernelOptions*Test,*KernelOptionsBuilder*Test'
ant -f java/manager-build.xml ivy && ant -f java/manager-build.xml checkstyle
```

## 11. Consequences for later steps

| Step | Change |
|---|---|
| 1.2 | Merge-only wiring: `addMissingOptions(user options, builder.distroOptions(tree))`; Agama path must not expect `install=` |
| 1.3 | Delegates `self_update` to `builder.selfUpdateOption(tree)`; keeps media-path `install=` local; must route by install type, not breed |
| 1.4 | Drops dead debian/ubuntu branch and rhel6 `kssendmac` branch; adds the shared textmode helper and the nopxe URL helper (first callers), composes onto the resolved cobbler options via `systemOptions` |
| 1.5 | **Narrows**: no `inst.auto` merge at all — only routes user-typed profile kernel options through `KernelOptionsList` (decision 4) |
| 1.6/1.7 | Call-site parity tests compare against legacy strings minus the documented normalisations (whitespace, dropped rhel6/debian branches) |

## Changelog

* 2026-10-08 — created from planning round 2: install-type-only routing,
  exception on unknown type, Debian builder dropped, `profileOptions` empty
  until G6, rhel6 compat dropped, `selfUpdateOption` returns
  `KernelOptionsList`, package layout fixed.
* 2026-10-08 — implemented: 6 classes, 34 DB-free unit tests green,
  checkstyle clean. Deviations from the initial design: the SUSE
  `text`→`textmode` helper is deferred to step 1.4 (no caller yet — no dead
  code), the org-global installer-updates fallback is only testable with a
  DB (covered by step 1.6/CI), and builder constructors are public so the
  factory in `installer/` can reach them. Also fixed on the way: a missing
  `assertContains` static import in `TreeCreateOperationTest` and a
  `jakarta.servlet-api:6.0.0` shadowing `tomcat-servlet-api:11.0.13`
  (Servlet 6.1) on the Maven test classpath (excluded the transitive 6.0
  API from the jstl dependency, matching the OBS packaging).
* 2026-10-08 — review follow-up: dropped `networkBoot`/`localBoot` from the
  builder contract (no Phase 1 caller composes a fresh boot line from
  builder parts; the initiate path works off the resolved cobbler options
  map) together with the base's default compositions and the nopxe URL
  helper. Tests trimmed accordingly (42 total green).
* 2026-10-08 — review follow-up: RHEL `distroOptions` adds `inst.repo=`
  unconditionally. The Anaconda `inst.*` options exist since RHEL 7, the
  minimal supported RHEL version, so the legacy `isRhel8OrGreater()` gate is
  dropped. 27 builder/factory tests green.
