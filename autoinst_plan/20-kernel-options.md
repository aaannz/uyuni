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
| `profileOptions(Profile)` | Profile-tied options. **Empty until the Cobbler exit (G6)** — cobbler's `tftpgen` owns the installer-selection option on generated PXE entries, so Uyuni must not duplicate it on profile records. Kept in the contract as the G6 flip-switch (empty default in the base, enabling later is a one-line override). | Yes |
| `systemOptions(SystemRecord)` | Per-system options (`inst.ks.sendmac` + `ks=`, `autoyast=`, `inst.auto=` for Agama) — only for boot lines Uyuni composes itself (initiate path), never written to cobbler records. | Yes |

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

Fires for **suse breed** profiles only, only when `kernelOptions`,
`kickstartHost` and `mediaPath` are all non-null:

```
if "install=" absent:   add install=http://<kickstartHost><mediaPath>
self_update: skip when profile's RESOLVED options contain self_update=0,
             or the string already has self_update=, or ksData == null;
             otherwise add self_update=<installer-updates URL | 0>
             (user-scoped child-channel lookup)
```

* Note the **different `install=` form**: `mediaPath` (the cobbler
  `ks_meta media_path`), not `/ks/dist/<label>`. Preserve exactly.
* `kernelOptions` at this point already contains the user options + `ksdevice=`
  produced by `KickstartScheduleCommand#getExtraOptions`.
* Result is set on the `SystemRecord` (plus `kernelOptionsPost` handling).
* Suggested split (inspiration, not mandate): keep the media-path `install=`
  local to the command (it is media-specific), delegate the `self_update`
  part to `builder.selfUpdateOption(tree)` so the logic lives once. For SLE16
  profiles this path must produce the Agama-compatible result (route by
  install type, not breed).

### 5.3 Initiate path — `domain/action/kickstart/KickstartInitiateAction#buildKernelOptions`

Salt local-reboot of a registered minion; input is the **fully resolved**
cobbler options map (distro + profile + system already merged) plus the
kickstart host:

```
breed = distro.getBreed()          # suse for SLES and SLE16
kopts = KernelOptionsList(resolvedMap)

if breed == "suse":
    if kopts has "textmode": remove "text"
    elif kopts has "text":   remove "text"; set textmode=1
remove "initrd"                    # no extra initrd param allowed

per breed:
  redhat/blank:  "inst.ks.sendmac ks=<systemAutoinstallUrl>"
                 (rhel6 kssendmac variant dropped — rhel6 compat removed)
  suse:          "autoyast=<systemAutoinstallUrl>"
  SLE16:         "inst.auto=<url>"     # committed behavior for os_version opensuse16*

if autoinstall (always true on this path):
    add info=http://<kickstartHost>/cblr/svc/op/nopxe/system/<systemName>
```

* Host is the **kickstart host**. Scheme `http://`.
* The SLE16 `inst.auto=` variant must be selected via install type (resolve the
  tree from the cobbler distro name `<treeLabel>:<orgId>`, which
  `prepareCobblerBoot` already parses) — breed is `suse` for both SLES15 and
  SLE16.
* The shared `text`→`textmode` transform belongs in one helper, applied only
  for suse breed; it is added in this step (first caller).
* Replace the hand-rolled map serializer with `KernelOptionsList(Map)` +
  `toString()` (fixes the legacy missing-space-before-`autoyast` quirk, which
  was masked by a trailing space; harmless normalization).
* The legacy debian/ubuntu branch is **removed**: no debian/ubuntu install
  type exists, the branch was unreachable (locked decision,
  `30-step-1.1-builders.md` §1.3).

### 5.4 Profile path — `frontend/action/kickstart/KickstartDetailsEditAction` → `CobblerProfileCommand#updateCobblerFields`

The profile's kernel options (UI field) are pushed to the cobbler profile on
every profile create/edit. Integration intent:

* Route the user-typed string through `KernelOptionsList` (idempotent
  handling, quoting safety).
* `builder.profileOptions(...)` is empty until the Cobbler exit (locked
  decision, `30-step-1.1-builders.md` §1.4): no `inst.auto` merge happens —
  cobbler's `tftpgen` owns the installer-selection option on PXE entries.
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
* Style gates: `mvn -pl java/core compile`, `checkstyle:check`.

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
