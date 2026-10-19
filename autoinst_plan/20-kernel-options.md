# Phase 1 Reference — Kernel Options Builders

> Reference catalogue for roadmap Phase 1 (`10-roadmap.md`): the builder
> contract, factory routing, and the exact legacy behavior/strings that the
> builders must reproduce. **Treat this as a behavioural specification, not a
> mandated implementation** — the code may be structured differently as long as
> the produced kernel command lines and the extension points match.

| | |
|---|---|
| Branch | `sles16_agama` |
| Created | 2026-10-07 |
| Related | `00-goals.md` §G0/§5.1/§5.8, `10-roadmap.md` Phase 1 |

---

## 1. Starting point

`com.suse.manager.autoinstallation.KernelOptionsList` (+
`KernelOptionsListTest`) already exists on this branch: an order-preserving,
multi-value kernel option container with string parsing (quoted values),
`Map<String,Object>` ingestion (values may be `String` or `List<String>` —
shaped for `CobblerObject#getResolvedKernelOptions()`), `setOption(key, value,
replace)` plus `setOptionIfNotPresent` / `setFlagIfNotPresent` /
`setOptionOrReplace`, `addOption(s)`, `addMissingOptions(other)`,
`applyOverrides(other)`, `removeOption`, `hasOption(key[, value])`, `getValue`
and a `toString()` rendering `key` / `key=value` space-separated. Everything
else in this document is to be built.

## 2. Builder contract

Interface `KernelOptionsBuilder` with per-OS-class implementations:

| Method | Produces | Idempotent? |
|---|---|---|
| `distroOptions(KickstartableTree)` | Boot-the-distro options (`install=`, `inst.repo=`, `root=live:`, `self_update=`/`inst.self_update=`). Result is **persisted** into `KickstartableTree.kernelOptions(_post)` and recomputed on every tree create/edit. | **Yes** (add-if-absent) |
| `selfUpdateOption(KickstartableTree)` | Just the installer-updates option (`self_update=` URL or `=0`; `inst.self_update=` for Agama). Extracted so the system path can reuse it; empty for RHEL. Returns a (possibly empty) `KernelOptionsList`. | Yes |
| `mediaOptions(String mediaUrl)` | The installation-source override pointing at the **session-tracked media URL** (`http://<kickstartHost><mediaPath>`, the per-session tinyurl built by the schedule flow). Key per OS class: `install=` (AutoYaST), `inst.repo=` (RHEL-like), `inst.install_url=` (Agama — overrides the product's default `installation_url`). Written onto cobbler system records (step 1.3); this is what routes installer package fetches through the kickstart session tracking (`DownloadFile` → `packageFetchCount`). | Yes (add-if-absent) |
| `profileOptions(Profile)` | Profile-tied options. **Empty until the Cobbler exit (G6)** — cobbler's `tftpgen` owns the installer-selection option on generated PXE entries, so Uyuni must not duplicate it on profile records. Kept in the contract as the G6 flip-switch (empty default in the base, enabling later is a one-line override). | Yes |
| `systemOptions(SystemRecord)` | Per-system options for boot lines Uyuni composes itself (initiate path, step 1.4): `inst.ks.sendmac` + `inst.ks=` (RHEL), `autoyast=` (AutoYaST), `inst.auto=` (Agama). Never written to cobbler records. | Yes |

Shared abstract base holds: configurable `serverFqdn` (callers pass the
*kickstart host*, which is not always `ConfigDefaults.getJavaHostname()`),
optional `User` context (see §5.1), URL helpers (dist tree, installer-updates
child, cobbler autoinstall service URL), and the empty `profileOptions`
default. Boot-line composition (`networkBoot`/`localBoot` from the original
round-1 design) was dropped: no Phase 1 call site composes a fresh boot line
from builder parts — the initiate path works off the resolved cobbler options
map (`30-step-1.1-builders.md` §3).

`KernelOptionsBuilderException` for null/invalid inputs.

## 3. Factory routing

Primary entry point: `getBuilder(KickstartInstallType)`:

1. `isSLES16OrGreater()` → **Agama** builder (checked *before* SUSE —
   `opensuse16.1` starts with `sles`-family prefixes and `isSUSE()` is true for
   it).
2. `isSUSE()` → **AutoYaST** builder.
3. `isRhel()` / `isFedora()` / `isGeneric()` → **RHEL** builder.
4. else → **throw `KernelOptionsBuilderException`** (no default builder).

**Routing is install-type-only (locked 2026-10-08, planning round 2)**: no
breed-based dispatch and no `getBuilderForBreed` fallback. `rhnKSInstallType`
contains no debian/ubuntu type and Uyuni-created distros cannot carry a debian
breed, so breed-based routing would only ever produce unreachable branches —
there is no Debian builder. Real Debian support remains from-scratch future
work (`00-goals.md` §5.8).

Callers that have a `KickstartableTree` in hand (all Phase 1 sites do, directly
or via the cobbler distro name `<treeLabel>:<orgId>`) must use the
install-type routing.

## 4. URL catalogue (legacy forms to reproduce)

| What | Form |
|---|---|
| Distro tree | `http://<fqdn>/ks/dist/<treeLabel>` |
| Installer-updates child tree | `http://<fqdn>/ks/dist/child/<childChannelLabel>/<treeLabel>` |
| Agama live root | `http://<fqdn>/ks/dist/<treeLabel>/LiveOS/squashfs.img` |
| Cobbler profile autoinstall | `http://<fqdn>/cblr/svc/op/autoinstall/profile/<profileName>` (see `KickstartUrlHelper.getCobblerProfileUrl`) |
| Cobbler system autoinstall | `http://<fqdn>/cblr/svc/op/autoinstall/system/<systemName>` |
| Cobbler nopxe signaling | `http://<fqdn>/cblr/svc/op/nopxe/system/<systemName>` |

Scheme is `http://` everywhere for now (Agama included — `00-goals.md` §5.1);
scheme configurability is deferred until post-Cobbler.

## 5. Legacy behavior catalogue (what the builders must reproduce)

### 5.1 Distro path — `manager/kickstart/tree/TreeCreateOperation#store`

Implemented in step 1.2 as `BaseTreeEditOperation#applyDistroKernelOptions()`,
called from `BaseTreeEditOperation#store()` — so it runs on tree create, edit,
XML-RPC update and rename. Guards make it idempotent; **user-typed options
must win** over computed ones. (Round-1 wording said the merge "effectively
re-runs on edit" — reality was that the edit path never recomputed and the
XML-RPC update wiped the computed options; step 1.2 made the wording literally
true. See `40-step-1.2-distro-path.md` for the decision record and the
parity register below.)

```
existing = KernelOptionsList(tree.kernelOptions)          # parsed keys
computed = builder.distroOptions(tree)                    # per §2/§4 builders
if validation of base path succeeded:                     # merged AFTER validateBasePath()
    existing.addMissingOptions(computed)
    tree.kernelOptions = existing.toString()
```

Behaviour per install type (identical strings to the legacy code, modulo the
normalizations in §5.1.1):

```
if installType.isSUSE():
    if SLE16 (isSLES16OrGreater):  root=live:<distTreeUrl>/LiveOS/squashfs.img  +  inst.self_update=<installer-updates URL | 0>
    else:                          install=<distTreeUrl>                        +  self_update=<installer-updates URL | 0>
else (rhel/fedora/generic):        inst.repo=<distTreeUrl>                      # unconditionally, RHEL8 gate dropped (step 1.1)
```

* `serverFqdn` here resolves the **local server FQDN** (canonical hostname of
  the server name set on the operation; blank → `ConfigDefaults.getJavaHostname()`
  fallback, covering the XML-RPC update/rename paths that never set it).
* Merge semantics: parsed-key `addMissingOptions` — user-typed keys win,
  computed keys are only added when absent. `kernelOptionsPost` is untouched.
* SLE16 detection in this path is via install type (`isSLES16OrGreater()`),
  matching the committed code.

#### 5.1.1 Documented normalizations vs. the legacy string building

* Parsed-key checks replace substring `contains()` checks: `nfsroot=` no
  longer suppresses `root=live:`; `inst.self_update=` no longer suppresses a
  `self_update=` add on the SLES15 path (and vice versa).
* No leading space when the user-typed options string is empty (legacy
  `StringJoiner` artifact).
* `inst.repo=` is added for *all* rhel/fedora/generic install types
  (incl. rhel_6/7, fedora18, generic_rpm) — locked step-1.1 deviation.
* The merge runs only after base-path validation succeeds (the legacy create
  path mutated options even when validation failed).
* Unknown install type → `KernelOptionsBuilderException` (unreachable for
  existing `rhnKSInstallType` labels).

### 5.2 System path — `manager/kickstart/cobbler/CobblerSystemCreateCommand#processKernelOptions`

Implemented in step 1.3 (`60-step-1.3-1.4-system-and-initiate-path.md`) —
**generic** (no breed gate; routing is install-type-only through the
factory), fires when `kernelOptions`, `kickstartHost`, `mediaPath` and
`ksData` are all non-null (`mediaPath != null` implies `ksData != null` —
only the schedule-flow constructor sets a media path):

```
options   = KernelOptionsList(kernelOptions)            # user part incl. ksdevice
builder   = factory.getBuilder(ksData.installType, kickstartHost, user)   # schedule user!
options.addMissingOptions(builder.mediaOptions("http://" + kickstartHost + mediaPath))
resolved  = KernelOptionsList(profile.resolvedKernelOptions)
if not (resolved has self_update=0 or inst.self_update=0):
    options.addMissingOptions(builder.selfUpdateOption(ksData.tree))
kernelOptions = options.toString()
```

* The `install=`-form media override carries the **per-session tinyurl**
  (`/ty/<token>` → `DownloadFile` → kickstart session tracking:
  `packageFetchCount`, state, `lastFileRequest`). `kickstartHost` is the
  **proxy** when one is configured (swapped in by `KickstartScheduleCommand`),
  so the system-record URL is already proxy-aware; the distro-record URLs
  rely on the proxy `tftp_wrapper.py` FQDN rewrite instead (orthogonal
  mechanisms that compose).
* The `self_update` part is **re-evaluated at schedule time with the schedule
  user** — custom installer-updates children are org/user-tied and a
  vendor/global lookup would miss them. The resolved opt-out is honored in
  both `self_update=0` and `inst.self_update=0` forms; user-typed keys win.
* Result is set on the `SystemRecord` (plus raw `kernelOptionsPost`).
* Behaviour per OS class (vs. the legacy suse-breed-only logic):
  SLES15-: `install=<mediaPath>` + `self_update=` (parity);
  SLE16: `install=<mediaPath>` replaced by `inst.install_url=<mediaPath>` and
  `self_update=` corrected to `inst.self_update=` (bug fix);
  RHEL/fedora/generic: `inst.repo=<mediaPath>` (new — session tracking now
  works for the RHEL family too, locked user decision).
* Parsed-key checks replace the substring `contains()` guards.

### 5.3 Initiate path — `domain/action/kickstart/KickstartInitiateAction#buildKernelOptions`

Implemented in step 1.4 (`60-step-1.3-1.4-system-and-initiate-path.md`):
Salt local-reboot of a registered minion (`bootloader.autoinstall` salt
state); input is the **fully resolved** cobbler options map (distro + profile
+ system already merged — including the system record's session media
override) plus the kickstart host and the tree (resolved from the cobbler
distro name in `prepareCobblerBoot`):

```
kopts   = KernelOptionsList(resolvedMap)
if distro breed is suse:  KernelOptionsHelper.applySuseTextmode(kopts)
kopts.removeOption("initrd")                # no extra initrd param allowed
builder = factory.getBuilder(tree.installType, kickstartHost)
kopts.addMissingOptions(builder.systemOptions(system))
if autoinstall: kopts.add info=KernelOptionsHelper.cobblerSystemNopxeUrl(host, system)
```

* Host is the **kickstart host**. Scheme `http://`.
* Installer selection per OS class via `systemOptions`: `inst.ks.sendmac`
  + `inst.ks=<systemAutoinstallUrl>` (RHEL), `autoyast=` (AutoYaST),
  `inst.auto=` (Agama). The rhel6 `kssendmac` variant and the dead
  debian/ubuntu branch are removed (locked step-1.1 decisions);
  `inst.ks=` is the user-locked RHEL 7+ form (the legacy `ks=` string is
  superseded — documented normalization).
* The `text`→`textmode` transform lives once in
  `KernelOptionsHelper.applySuseTextmode`, applied only for suse breed
  (first caller: this path).
* If the user typed an installer-selection option in the profile options, it
  wins — legacy appended a duplicate-key occurrence (normalization).
* The hand-rolled map serializer is replaced by `KernelOptionsList(Map)` +
  `toString()` (fixes the legacy missing-space-before-`autoyast` quirk and
  the trailing-space artifact; harmless normalization).

### 5.4 Profile path — `frontend/action/kickstart/KickstartDetailsEditAction` → `CobblerProfileCommand#updateCobblerFields`

The profile's kernel options (UI field) are pushed to the cobbler profile on
every profile create/edit. Implemented in step 1.5
(`50-step-1.5-profile-path.md`):

* The user-typed string is routed through `KernelOptionsList` (idempotent
  handling, quoting safety) at the `updateCobblerFields` convergence point —
  the single writer is the profile details UI
  (`KickstartDetailsEditAction#processCobblerFormValues`); all automated
  callers (taskomatic sync, wizard create, XML-RPC) leave the field null and
  skip the block. Whitespace normalization applies on save (§5.1.1);
  `<<inherit>>`, empty string and quoted values round-trip unchanged.
* `builder.profileOptions(...)` is empty until the Cobbler exit (locked
  decision, `30-step-1.1-builders.md` §1.4): no `inst.auto` merge happens —
  cobbler's `tftpgen` owns the installer-selection option on PXE entries. The
  merge runs via the factory (routed by `ksData.getInstallType()`, FQDN via
  the shared `KickstartHostHelper.getServerFqdn(...)`: provided →
  canonicalized, null/empty → configured Java hostname unchanged); routing
  failures (`KernelOptionsBuilderException`) propagate — consistent with the
  distro path, unreachable for existing `rhnKSInstallType` labels.
* `postKernelOptions` keeps flowing as a raw string (untouched).
* Cobbler profile inheritance is not used by Uyuni; always writing explicit
  options is safe.

### 5.5 Channel lookup discrepancy (preserve legacy semantics)

The distro/system paths look up installer-updates children **user-scoped**
(`channel.getAccessibleChildrenFor(user)`), while a naive builder would use
`ChannelFactory.listAllChildrenForChannel` (org/global). Results can differ.
Resolution: give the abstract builder an optional `User` context; when set,
use the user-scoped lookup, else fall back to the org-global one. Set the user
where the call site has one (`TreeCreateOperation`,
`CobblerSystemCreateCommand`).

## 6. Cobbler `tftpgen` interaction (why idempotency matters)

With breed `suse`, cobbler's `tftpgen.build_kernel_options` adds the
installer-selection option itself when generating PXE entries: os_version
values on its AutoYaST denylist get `autoyast=`, everything else (incl.
`opensuse16*`) gets `inst.auto=` (cobbler PR #177). Consequences:

* As long as Cobbler generates PXE entries, Uyuni must not duplicate that
  option on distro/profile/system records (keep builders idempotent and do
  not add `autoyast=`/`inst.auto=`/`ks=` there; `systemOptions` carries them
  only for boot lines Uyuni composes itself).
* The Salt initiate path (§5.3) is the Uyuni-side equivalent and must keep
  adding the option itself (the minion builds its own boot entry).
* After the Cobbler exit (G6), Uyuni owns the whole command line — the
  builders are the single place that must then emit these options for PXE
  entries too. Design the builders so flipping this on is one change, not a
  call-site sweep.
* Saltboot bypasses all of this by using the `generic` breed and composing its
  complete command line itself (reference pattern).

## 7. Test outline

* **Pure-logic (run locally):** `KernelOptionsList` additions; each builder's
  `distroOptions`/`selfUpdateOption`/`profileOptions`/`systemOptions` with
  mocked `KickstartableTree`/`Profile`/`SystemRecord`/`Channel`; factory
  routing (incl. SLE16-before-SUSE precedence); SUSE text→textmode helper.
* **Entity-level:** `KickstartInstallType` predicates + cobbler breed/os
  version for `opensuse16.1` (existing tests on this branch cover parts).
* **Call-site parity (CI where DB/cobbler needed):** for each breed/install
  type, assert the produced kernel-options string equals the legacy string
  (modulo the documented whitespace normalization); user-typed options win
  over computed ones; idempotency under repeated store() calls.
* Style gates: `mvn -f java compile checkstyle:checkstyle` (ant no longer
  used for checkstyle).

## 8. E2E verification notes

* Verify on the shared agent VM per `00-goals.md` §1 protocol (single VM,
  manually recycled, coordinate with parallel agents; record deployed
  package versions).
* **SLE16 caveat:** Agama PXE install only works if the VM's cobbler includes
  openSUSE/cobbler PR #177 (`00-goals.md` §5.1). Check
  `rpm -q cobbler` / the cobbler changelog on the VM first; if absent, limit
  SLE16 validation to the Salt-initiate path or unit-level evidence and note
  it in the session log.
* Regression matrix for this phase: SLES15 tree/profile schedule (AutoYaST
  strings unchanged), RHEL7+ tree (`inst.repo=`), SLE16 tree (`root=live:` +
  `inst.self_update=`), minion re-provisioning via Salt initiate for SLES15
  and SLE16.

## 9. Test infrastructure notes (lessons from step 1.3/1.4)

Non-obvious facts about the DB-backed test setup — read before writing tests
against the cobbler mock or the kickstart fixtures.

### 9.1 Cobbler mock (`java/core/src/test/java/org/cobbler/MockConnection.java`)

* Kernel options strings are stored **raw** on modify (except `""`, which the
  mock turns into an empty dict, and `<<inherit>>`, which it interprets as
  inherit on read) — while `CobblerObject#getKernelOptions()` is typed
  `Optional<Map<String, Object>>`. The established profile tests assert the
  raw string; bridge with `String.valueOf(rec.getKernelOptions().get())` or
  parse via `KernelOptionsList` — do not cast.
* `get_item_resolved_value(uid, key)` resolves the attribute with a proper
  inheritance merge — system → profile → distro, lower objects win, string
  values parsed the way cobbler parses option strings. Repaired in step 1.3:
  before that it could never match (it searched by the attribute key as if
  it were a uid) and every resolved getter silently returned `null`.
* Inheritance references resolve **by name across all collections** (the
  system stores the profile *name*, the profile the distro *name*). A
  profile named exactly like its distro — the saltboot fixtures do this via
  `SaltbootUtils.makeCobblerName(org, BOOT_IMAGE)` for both — becomes its
  own parent; the visited-set cycle guard keeps resolution finite (the
  merged result is then just the item's own options).
* Resolved maps are `HashMap`s — **option order is not stable**, for the
  legacy serializer and `KernelOptionsList(Map)` alike. Assert boot lines
  composed from resolved options by sorted option tokens, not exact strings.

### 9.2 Fixture cookbook (DB-backed)

* Install-type labels in `rhnKSInstallType`: `sles15generic` (SLE15),
  `opensuse16.1` (SLE16/Agama), `rhel_7`, `generic_rpm`; the cobbler breed
  is `suse` for both SLES15 and SLE16.
* `KickstartDataTest.createKickstartWithDefaultKey(org)` builds ksdata +
  defaults + channel + tree (incl. mock cobbler distro/profile) + a default
  session. Change the install type afterwards via
  `ksdata.getTree().setInstallType(...)` + save, and align the mock distro's
  `setBreed`/`setOsVersion` when a test depends on them.
* Installer-updates child for the self_update lookups:
  `ChannelTestUtils.createChildChannel(user, tree.getChannel())` +
  `channel.setInstallerUpdates(true)`; the schedule-scoped lookups go
  through `getAccessibleChildrenFor(user)`.
* System record options round-trip: build the command, `store()`, then read
  back via `SystemRecord.lookupById(con, server.getCobblerId())` (store
  persists `server.setCobblerId`).

### 9.3 Regression map for the system path and the mock

Touching `CobblerSystemCreateCommand`, the builders, or `MockConnection` —
run at least: `CobblerSystemCreateCommandTest`, `CobblerCommandTest` (16,
incl. the step-1.5 profile-options cases), `SystemHandlerProvisioningTest`
(exercises the full `provisionSystem` flow and asserts the media override),
the saltboot suites (`SaltbootUtilsTest`, `PXEEventTest`,
`SaltbootMigrationUtilsTest` — they hit the mock inheritance path), the
tree-op suites (`TreeCreateOperationTest`,
`BaseTreeEditOperationKernelOptionsTest`), the builder/factory/list suites,
and `KickstartInitiateActionTest` (initiate-path parity).

### 9.4 Real-cobbler test mode (step 1.6)

Opt-in mode running the phase-1 DB-backed suites against the cobbler shipped
in the pgsql-4eclipse test container — design and full findings:
`70-step-1.6-tests.md` §3/§8. Essence:

* **Activation:** `-Duse.real.cobbler=true` (read by
  `KickstartTestUtils.useRealCobbler()`); `setupTestConfiguration` then
  re-points the `CobblerXMLRPCHelper`/`CobblerConnection` config keys to the
  real classes and skips (assumeTrue) when cobbler is unreachable. Unset ⇒
  byte-identical mock behaviour. **Every test must clean up cobbler
  afterwards** (`KickstartTestUtils.cleanUpCobbler()` in `@AfterEach`):
  real cobbler rejects the fixtures' duplicate fixed MAC.
* **Container setup:** `java/buildconf/test/cobbler/setup-cobbler-test.sh`
  (run via `podman exec` inside the test container) starts cobblerd +
  apache (`/cobbler_api` proxy) + the accept-all auth shim
  (`cobbler-auth-shim.py` emulating the Uyuni side of the
  `authentication.spacewalk` callback protocol), links the template dirs and
  patches two cobbler 3.3.3 defects (container-only, `cbltest-patch`
  marker). Test `rhn.conf` needs `cobbler.port = 8080` (published port);
  `/tmp/kickstart` and `/tmp/opt` must be bind-mounted `:Z` into the
  container (cobbler validates kernel/initrd and template paths there).
* **Assertion rules (mock vs real):** real cobbler returns kernel options
  as unordered maps — compare sorted tokens; blank option values round-trip
  as `~` — the test helpers normalize `=~` back to bare flags; the mock
  keeps raw strings and lists unsaved profiles, real cobbler only saved
  ones and validates kernel/initrd/template paths at save/modify time.
* **SLE16 caveat in real mode:** the VM's cobbler lacks the `opensuse16.1`
  signature (PR #177) — the five SLE16 fixtures skip via
  `KickstartTestUtils.cobblerSupportsSles16()`; they run in mock mode and
  CI.
* `java-unittests-pgsql.sh` (ant-based CI harness) is mock-only: it never
  starts cobblerd and is unaffected by this mode.
