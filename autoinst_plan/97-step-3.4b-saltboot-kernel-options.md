# Step 3.4b — Saltboot PXE/Kernel Options via Builders

> **Revision (planning round 9, after the split):** this step now follows
> step 3.4a ([`96-step-3.4a-saltboot-image-tree.md`](96-step-3.4a-saltboot-image-tree.md)).
> Saltboot distros are image-based `KickstartableTree`s with install type
> `saltboot`, so §3.1 changes: `KernelOptionsBuilderFactory.getBuilder`
> routes `isSaltboot()` (checked first) to `SaltbootKernelOptionsBuilder`,
> `distroOptions(imageTree)` receives the real tree (and is persisted into
> its `kernel_options`, then pushed to the cobbler distro), and the
> "direct construction / no factory route" text below is superseded. Branch
> and terminal stay bound at construction (a factory overload or a
> `with…` step on the returned builder — settled at 3.4b start). The rest
> (K2–K5) is unchanged.

> Plan for roadmap step 3.4b (`10-roadmap.md` Phase 3): route the saltboot
> kernel option construction (`SaltbootUtils`, `PXEEventMessageAction`,
> `ProxyBackupEventAction`) through the G0 builder hierarchy and read the
> option sources from the saltboot domain. Closes the Phase 1 deferral
> "Saltboot PXE option maps".

| | |
|---|---|
| Branch | `sles16_agama` |
| Created | 2026-10-19 |
| Status | PLANNED (planning round 9) |
| Related | `00-goals.md` §G0/§G3/§5.9, `20-kernel-options.md` §2/§6, `90-step-3.1-saltboot-model.md` §6.2, `95-step-3.2-saltboot-implementation.md` (S1) |

---

## 1. Current state (verified 2026-10-19, tree `3484a7e3ee9`)

Java paths are relative to `java/core/src/main/java/`.

### 1.1 Option build sites

| Site | What it builds |
|---|---|
| `SaltbootUtils.createSaltbootDistro` (`com/suse/manager/saltboot/SaltbootUtils.java:249`) | distro options, literal `panic=60 splash=silent`, breed `generic` |
| `SaltbootUtils.getKernelOptions(ServerGroup)` (L409) → private `getKernelOptions(...)` (L430) | branch options for the cobbler **group profile**: `MINION_ID_PREFIX=<group name>`, `MASTER=<download server>`, `DISABLE_ID_PREFIX=1`, `DISABLE_UNIQUE_SUFFIX=1`, one of `USE_FQDN_MINION_ID=1` / `DISABLE_HOSTNAME_ID=1` / `USE_MAC_MINION_ID=1`, then the user's `default_kernel_parameters` applied **over** the computed keys (`putAll`). Source: formula pillar first, domain branch otherwise (decision S1). Formula path with empty `download_server`: bare `MASTER` (null value). Domain path without a proxy: no `MASTER`. |
| `PXEEventMessageAction.execute` (`com/suse/manager/saltboot/PXEEventMessageAction.java:54-64`) | terminal string `root=<root> [salt_device=<dev>] [<event kernel params>]` by concatenation |
| `SaltbootUtils.createSaltbootSystem` (L616) | system record options = **group profile options read back from cobbler** (`group.getKernelOptions()`) with the terminal string applied over them (`putAll`); the record's profile is the *image* profile, so the branch options must be on the record itself |
| `ProxyBackupEventAction.convertSinglePxeEntry` (`com/suse/proxy/event/ProxyBackupEventAction.java:343`) | RBS → containerized conversion: passes the legacy PXE entry's raw `args` as the terminal string |

Parsing is done by the private `SaltbootUtils.splitStringIgnoreQuotes` (a
regex that also honors single quotes; duplicate keys: last wins). Transport to
cobbler is a `Map` (unordered; every kickstart path sends a string).

### 1.2 Contracts that must hold

* **Proxy filter:** `containers/proxy-tftpd-image/tftp_wrapper.py`
  (`HttpResponseDataFilteredPXE` / `…Grub`) recognizes saltboot entries by the
  substring ` MASTER=<proxy fqdn>` plus `MINION_ID_PREFIX`. An older proxy
  container must keep working (`00-goals.md` §5.8), so both keys and the
  `MASTER=` value form stay unchanged.
* **Cobbler inheritance:** distro (`panic=60 splash=silent`) → image profile →
  system record. Group profiles parent the image profile and are used for the
  not-yet-deployed (unknown terminal) PXE entry.
* `SaltbootTerminal` (step 3.2) exists with `boot_image`, `root_device`,
  `salt_device`, `terminal_kernel_parameters`, `last_pxe_update`,
  `last_result`, but **nothing writes those columns yet** — only the force
  flags are used.

### 1.3 Doc drift found

* `20-kernel-options.md` §1 lists `KernelOptionsList#applyOverrides`; that
  method does not exist (the API is `setOption*`, `addMissingOptions`,
  `addOption(s)`, `removeOption`, `hasOption`, `getValue`). §1 is corrected
  with this step.

## 2. Decisions (user, planning round 9, 2026-10-19)

| # | Decision |
|---|---|
| K1 | The saltboot builder **joins the `KernelOptionsBuilder` interface** (extends `AbstractKernelOptionsBuilder`); the interface may be modified, smaller modifications preferred. |
| K2 | The builder's `serverFqdn` is the FQDN of the proxy (branch server) the terminal connects through; the builder's **`User` is the creator of that proxy system** (`Server#getCreator()`, may be null). |
| K3 | Terminal options are built from the domain: `pxe_update` handling **first persists** the reported values into `SaltbootTerminal`, then the builder reads them (pulls this part of the 3.5 hand-off forward). |
| K4 | System record options are **recomputed** via the builder from the branch sources (formula-first per S1, else the domain branch) — no read-back of the cobbler group profile options. |
| K5 | Cobbler transport stays a **`Map`** (byte-compatible records); builders produce `KernelOptionsList`, converted at the cobbler boundary. |

## 3. Design

### 3.1 Fitting into the interface (K1) — bound context, no signature change

The interface methods take kickstart/cobbler objects; saltboot has no
`KickstartableTree`, and its branch/terminal inputs are domain objects. The
smallest change is to **bind the saltboot context at construction** and keep
every interface signature as is:

```java
public class SaltbootKernelOptionsBuilder extends AbstractKernelOptionsBuilder {
    // proxyFqdn -> serverFqdn (MASTER=), proxyCreator -> user (K2)
    // branch and terminal are optional: a distro-only builder has neither
    public SaltbootKernelOptionsBuilder(String proxyFqdn, User proxyCreator,
                                        SaltbootBranch branch, SaltbootTerminal terminal)
}
```

| Interface method | Saltboot meaning | Result |
|---|---|---|
| `distroOptions(tree)` | boot-the-image-distro options; `tree` unused (null allowed) | `panic=60 splash=silent` |
| `profileOptions(profile)` | branch options for the cobbler **group profile**; `profile` unused (as in every existing builder) | `MINION_ID_PREFIX=<branch group name>`, `MASTER=<serverFqdn>` (bare `MASTER` flag when no FQDN, §3.4), naming flags; the parsed `default_kernel_parameters` **win** (`user.addMissingOptions(computed)`) |
| `systemOptions(record)` | full per-terminal options for the cobbler **system record**; `record` unused | `root=<root_device>`, `salt_device=<salt_device>` (if set), parsed `terminal_kernel_parameters`, all winning over `profileOptions` (`terminal.addMissingOptions(branch)`) |
| `selfUpdateOption(tree)`, `mediaOptions(url)` | not applicable | empty `KernelOptionsList` |

Missing bound inputs throw `KernelOptionsBuilderException` (via `require`):
`profileOptions` needs a branch, `systemOptions` a branch and a terminal with a
root device.

**Interface modifications (javadoc only):**

* `systemOptions` — "never written to Cobbler records" becomes "never written
  to Cobbler records *of Cobbler-rendered breeds*; builders for the `generic`
  breed own the whole command line and write it" (`20-kernel-options.md` §6:
  saltboot is the reference pattern for post-Cobbler ownership).
* `distroOptions` — the tree parameter may be null for builders that are not
  backed by a kickstart tree.
* Class javadoc — builders may bind their context at construction.

**Obtaining the builder:** saltboot has no install type, so it does not go
through `KernelOptionsBuilderFactory.getBuilder(installType, …)` (that factory
stays install-type-only, locked step 1.1). `SaltbootUtils` gets one private
helper that resolves the sources and constructs the builder (§3.2). The
distro-only builder is `new SaltbootKernelOptionsBuilder(null, null, null,
null)` — the image distro is org-wide, not tied to a proxy.

Rejected alternative: changing the parameter types (e.g. a generic context
object per method) — touches all three Phase 1 callers and all builders for no
gain in the kickstart paths.

### 3.2 Branch sources (K2, K4, S1)

Private `SaltbootUtils.branchBuilder(ServerGroup group, SaltbootTerminal terminal)`:

1. **Formula pillar present** (`formula-saltboot-group` on the group,
   authoritative per S1): a **transient, never-saved** `SaltbootBranch(group)`
   carries naming flags + `default_kernel_parameters` from the form data;
   `proxyFqdn` = the form's `download_server` text verbatim (exact `MASTER=`
   parity); `proxyCreator` = creator of the proxy found by that FQDN
   (`ServerFactory.listByFqdn` + `isProxy()`, no notification — the
   dual-write already reports misses), else null. The mapping reuses
   `SaltbootFormulaMapper` by splitting a side-effect-free `fillBranch(branch,
   formData)` out of `applyBranchFormula` (proxy lookup and save stay in
   `applyBranchFormula`).
2. **Domain branch:** `SaltbootFactory.lookupBranch(group)`; `proxyFqdn` =
   `SaltbootTerminalResolver.proxyFqdn(branch)` (the single FQDN resolution
   point, D2 extension point); `proxyCreator` = `branch.getProxy().getCreator()`.
3. Neither → `SaltbootException("Missing saltboot group pillar")` (unchanged).

Used by `updateGroupProfile` (`profileOptions`) and `createSaltbootSystem`
(`systemOptions`). `createSaltbootSystem` resolves the branch group by name
(`ServerGroupFactory.lookupByNameAndOrg(saltbootGroup, org)`); a missing group
throws `SaltbootException` like a missing group profile does today. The check
that the cobbler group profile exists is kept (it is the unknown-terminal
entry the proxy serves).

### 3.3 Terminal persistence (K3)

`PXEEventMessageAction.execute`:

1. `SaltbootFactory.lookupOrCreateTerminal(minion.getId())`; set
   `boot_image`, `root_device` (event `root`), `salt_device`,
   `terminal_kernel_parameters` (event `kernel_parameters`),
   `last_pxe_update = now`.
2. `SaltbootUtils.createSaltbootSystem(minion, terminal, group, hwAddresses)`
   (new signature; builds via `systemOptions`).
3. `last_result` = `"OK"` or the failure message (same message as the
   `PXEEventFailed` notification).
4. Flag reset unchanged (`resetSaltbootRedeployFlags`; the
   reset-after-failure question stays with 3.5, `90-…` §1.9).

The empty-root check stays before persistence (no terminal row update for an
invalid event; the notification as today).

`ProxyBackupEventAction.convertSinglePxeEntry` (RBS conversion, slated for
removal, `90-…` §6.5): persists the legacy entry's `args` as
`terminal_kernel_parameters` (root/salt device unknown → null) and calls the
same `createSaltbootSystem`. Since `systemOptions` requires a root device, the
builder accepts a terminal without `root_device` **only** when
`terminal_kernel_parameters` is set (the RBS `args` carry the full legacy
line incl. `root=`); otherwise it throws. Parity: today these `args` are
applied over the group options exactly the same way.

### 3.4 Normalizations (documented, vs. the legacy maps)

* **Parser:** `splitStringIgnoreQuotes` is removed; parsing goes through
  `KernelOptionsList`. Double-quoted values keep their quotes in the value
  (same as today). Single quotes are no longer grouping characters — the
  Linux kernel only honors double quotes on the command line, so
  single-quoted values containing spaces were already broken at boot.
* **Duplicate keys:** repeated keys inside one user string are kept
  (multi-value → `List` in the map → rendered twice by cobbler); legacy kept
  only the last. Precedence between layers is unchanged: user default
  parameters over computed branch keys, terminal values over branch values.
* **No proxy:** the builder emits a bare `MASTER` flag when it has no FQDN —
  legacy formula behavior; for the domain path this is new but only affects
  branches without a proxy, which cannot be served by any proxy anyway
  (`tftp_wrapper.py` needs `MASTER=<fqdn>`) and already raise the "pick a
  proxy" notification.
* **System record freshness:** branch options on the record are computed from
  the current formula/domain data instead of the last-saved cobbler group
  profile — identical unless the cobbler group profile was edited by hand.
* **Order:** cobbler maps were unordered before and stay unordered.

### 3.5 Cobbler boundary (K5)

New `KernelOptionsList#toMap()` returning `Map<String, Object>` in insertion
order (`LinkedHashMap`): single occurrence → `String`, repeated key →
`List<String>`, flag → `""` (the legacy flag form of
`splitStringIgnoreQuotes`; real cobbler stores blank values as a flag, see
`20-kernel-options.md` §9.4). The bare `MASTER` was sent as `null` before;
`""` and `null` round-trip to the same flag in real cobbler, and the mock
stores what it gets (tests assert via `KernelOptionsList`).

Call sites: `createSaltbootDistro` keeps `setKernelOptions(Optional.of(
builder.distroOptions(null).toString()))` — a string, as today; group profile
and system record use `toMap()`.

### 3.6 Files

| File | Change |
|---|---|
| `com/suse/manager/autoinstallation/builder/SaltbootKernelOptionsBuilder.java` | new |
| `com/suse/manager/autoinstallation/builder/KernelOptionsBuilder.java` | javadoc (§3.1) |
| `com/suse/manager/autoinstallation/KernelOptionsList.java` | `toMap()` |
| `com/suse/manager/saltboot/SaltbootUtils.java` | builder use; `branchBuilder`; `createSaltbootSystem(minion, terminal, group, hwAddresses[, con])`; remove `splitStringIgnoreQuotes` and the private `getKernelOptions` pair |
| `com/suse/manager/saltboot/PXEEventMessageAction.java` | terminal persistence (§3.3) |
| `com/suse/proxy/event/ProxyBackupEventAction.java` | terminal persistence + new call |
| `com/suse/manager/autoinstallation/saltboot/SaltbootFormulaMapper.java` | split side-effect-free `fillBranch` |
| `spacewalk-java.changes.oholecek.saltboot_kernel_options` | changelog fragment |

No schema change, no API change (`saltboot.getTerminalState` now returns the
populated columns — documented values already).

## 4. Tests

* **DB-free:** `SaltbootKernelOptionsBuilderTest` — distro constant; branch
  options per naming mode, flags, user parameters winning over computed keys,
  bare `MASTER` without FQDN, quoted values; system options (root/salt
  device, terminal params winning over branch, RBS mode without root, missing
  inputs → exception); `selfUpdateOption`/`mediaOptions` empty;
  `KernelOptionsListTest` for `toMap()` (order, multi-value, flags).
* **DB-backed (agent VM, pgsql-4eclipse):** `SaltbootUtilsTest` (group
  profile + system record parity, formula and domain paths, transient branch
  never persisted), `PXEEventTest` (terminal columns written, `last_result`
  on success/failure, record options), `SaltbootFormulaMapperTest`
  (`fillBranch` split), plus the §9.3 regression map of
  `20-kernel-options.md` (saltboot suites + `SaltbootMigrationUtilsTest`,
  builder/factory/list suites) and the step-3.2 saltboot suites.
* Checkstyle (main + tests) as in step 3.2.

## 5. E2E (agent VM)

* Coordinate the VM window; record deployed versions. Note from step 3.2: the
  VM runs an overlaid jar, and default access groups are duplicated in its DB
  (the dedupe needs the user's go-ahead) — check before creating groups via
  the API.
* With a branch-deployed backend: create a branch via the `saltboot` API (and
  one through the formula), check the cobbler group profile
  (`cobbler profile report`) and the generated grub/pxelinux entry for
  ` MASTER=<proxy fqdn>` + `MINION_ID_PREFIX` (proxy filter contract).
* Fire a `suse/manager/pxe_update` event for a registered minion (if one is
  available on the VM) and check the `SaltbootTerminal` row and the system
  record options; otherwise rely on `PXEEventTest` and record the gap.

## Changelog

* 2026-10-19 — planning round 9: current state verified, decisions K1–K5
  taken by the user, design written.
