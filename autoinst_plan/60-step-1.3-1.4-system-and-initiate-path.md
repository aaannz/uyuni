# Step 1.3 + 1.4 Design — System Path & Initiate Path

> Implementation design for roadmap steps 1.3 and 1.4 (`10-roadmap.md`
> Phase 1), planned together in one session (planning round 5 decision). The
> behavioural specification remains [`20-kernel-options.md`](20-kernel-options.md);
> this document fixes the call-site wiring design, records the research into
> the per-session media URL and the locked decisions for both steps.

| | |
|---|---|
| Branch | `sles16_agama` |
| Created | 2026-10-09 |
| Status | DONE (2026-10-09) |
| Related | `00-goals.md` §G0/§5.1/§5.8, `10-roadmap.md` steps 1.3/1.4, `20-kernel-options.md` §2/§5.2/§5.3, `30-step-1.1-builders.md` |

---

## 1. Research findings (verified against the code, 2026-10-09)

### 1.1 Legacy system path — `CobblerSystemCreateCommand#processKernelOptions`

`java/core/src/main/java/com/redhat/rhn/manager/kickstart/cobbler/CobblerSystemCreateCommand.java:381`.
Fires only for **suse breed** profiles and only when `kernelOptions`,
`kickstartHost` and `mediaPath` are all non-null:

1. `install=http://<kickstartHost><mediaPath>` is appended when the user
   options string lacks the substring `install=`.
2. `self_update=<installer-updates URL | 0>` is appended unless the profile's
   **resolved** options contain `self_update=0`, or the string already
   contains the substring `self_update=` (which also matches
   `inst.self_update=`), or `ksData == null`. The installer-updates child
   lookup is **user-scoped** (`getAccessibleChildrenFor(user)` — the schedule
   user).
3. The mutated string lands on the system record via
   `processOptionalProperties` (`rec.setKernelOptions`), `kernelOptionsPost`
   passes through raw. Callers: `KickstartScheduleCommand` (schedule + cobbler
   -only branches), `SSMCreateRecordCommand`, `SystemHandler:6924`,
   `ScheduleKickstartWizardAction`, `CobblerVirtualSystemCommand`,
   `CobblerUnregisteredSystemCreateCommand`. Only the schedule flow ever sets
   a `mediaPath`; every other caller leaves it null and is a no-op today.

### 1.2 What `mediaPath` is: the per-session tinyurl (session tracking)

`KickstartScheduleCommand` passes
`KickstartUrlHelper#getKickstartMediaPath(kickstartSession, scheduleDate)`
(`KickstartScheduleCommand.java:601-604`) — a `/ty/<8-char-token>` path
(`KickstartUrlHelper.java:298`, `TinyUrl#computeTinyPath`) created with
`expires = scheduleDate + java.server.satellite.tiny_url_timeout` (default
4 h, `CommonFactory.java:117`). Expiry is **not enforced** on lookup
(`TinyUrlAction`, `DownloadFile`) — metadata only.

Purpose: **kickstart session progress tracking**, not proxy handling.

* Apache rewrites `/ty/*` → `DownloadFile`
  (`spacewalk/config/etc/httpd/conf.d/zz-spacewalk-www.conf:84`); the token
  resolves to `/ks/dist/session/<SessionSwap-encoded-sessionId>/<treeLabel>`.
* `DownloadFile` serves tree media from disk and advances the
  `KickstartSession` on every fetch: state `STARTED`/`IN_PROGRESS`,
  `packageFetchCount++`, `lastFileRequest` (`DownloadFile.java:659`,
  `updateKickstartSession`). This drives the kickstart progress reporting.
* Only the option carried on the **system record** routes the installer's
  package fetches through the tracking URL (system > profile > distro
  precedence in tftpgen). Profiles that pin their own repository URLs bypass
  the tinyurl — consistent with the observed "tracked session does not
  advance" reports for AutoYaST profiles with static distro URLs.
  RHEL installs fetched via the static distro URL were never tracked.

### 1.3 Proxy interplay (`containers/proxy-tftpd-image/tftp_wrapper.py`)

* The wrapper fetches cobbler-rendered `pxelinux.cfg/*` and `grub/*` menus
  from the server over HTTP and rewrites **FQDN tokens only** (word-boundary
  `server_fqdn` → `proxy_fqdn`, plus the configured `replace_fqdns` list);
  saltboot entries (`MASTER=` + `MINION_ID_PREFIX`) are filtered instead of
  rewritten.
* The `/ty/<token>` path contains no FQDN and passes through untouched — and
  the system record's media URL is already written with the **proxy FQDN** at
  schedule time (`KickstartScheduleCommand.java:568-571` swaps
  `getProxyHost()` into `kickstartHost`). The wrapper rewrite is a no-op for
  this option; it covers the distro-record URLs (`install=`/`self_update=`/
  `root=live:`/`inst.self_update=`, step-1.2 output, server FQDN).
* Conclusion: the per-session tinyurl and the proxy FQDN rewrite are
  orthogonal mechanisms that compose cleanly. The media override stays.

### 1.4 `mediaPath != null` ⟹ `ksData != null`

Only the `(user, server, ksData, mediaPath, activationKeys)` constructor sets
`mediaPath`, and it NPEs on a null `ksData`
(`CobblerSystemCreateCommand.java:129-139`); all other constructors (incl. the
`CobblerVirtualSystemCommand` delegates) leave it null. The legacy
`ksData == null` skip is therefore unreachable in the media branch — routing
by `ksData.getInstallType()` is safe.

### 1.5 SLE16 defects in the legacy system path

* It always emits the YaST-form `self_update=` (breed gate); Agama consumes
  `inst.self_update=`.
* The resolved opt-out check misses `inst.self_update=0` — an Agama-form
  opt-out on the tree/profile still results in a computed
  `self_update=<url>` on the system record.

### 1.6 Legacy initiate path — `KickstartInitiateAction#buildKernelOptions`

`java/core/src/main/java/com/redhat/rhn/domain/action/kickstart/KickstartInitiateAction.java:109`.
Works off `sys.getResolvedKernelOptions()` (distro + profile + system
options merged by cobbler) — the SUSE `text`→`textmode` transform, drops
`initrd`, appends the installer-selection option per breed/os_version
(`inst.ks.sendmac ks=` for redhat incl. a rhel6 `kssendmac` variant,
`autoyast=` for suse, `inst.auto=` for `opensuse16*`, a dead debian/ubuntu
branch) and the `info=` nopxe URL. The pillar feeds the
`bootloader.autoinstall` salt state (`SaltParameters.KICKSTART_INITIATE`);
the grub templates render `pillar.get('kopts')` into the `uyuni-reinstall`
boot entry. `prepareCobblerBoot` already resolves the `KickstartableTree`
from the distro name `<treeLabel>:<orgId>` (lines 90-95) — the install-type
routing key is available at the call site.

### 1.7 Agama installation-source override — `inst.install_url`

Per the [Agama boot options
reference](https://agama-project.github.io/docs/user/reference/boot_options):

* `inst.install_url` **overrides the product's default `installation_url`**
  (products.d) — i.e. the installation repository Agama installs packages
  from. It is *not* the boot-media location (that is `root=live:`), and
  `inst.repo` is not an Agama option.
* Comma-separated URL lists are accepted; the setting "impacts all
  products".

So the session-tracking media override for Agama is
`inst.install_url=http://<kickstartHost><mediaPath>` — semantically the
SLE16 equivalent of the AutoYaST `install=` override (package source through
the session-tracked URL), with the product-default-override caveat.

### 1.8 RHEL `systemOptions` — `inst.ks=` is the committed, correct form

`RhelKernelOptionsBuilder#systemOptions` emits `inst.ks.sendmac inst.ks=<url>`
(RhelKernelOptionsBuilder.java:73-78; its test asserts exactly that). The
user locked this: `inst.ks` is what RHEL 7+ consumes. The spec
(`20-kernel-options.md` §2/§5.3) and the roadmap step-1.2 changelog still say
`ks=` — the docs are stale, the code is right. Consequence for the initiate
path: RHEL boot lines composed by Uyuni become
`inst.ks.sendmac inst.ks=<url>` (documented delta vs the legacy `ks=`).

### 1.9 Test infra

`CobblerCommandTestBase` runs DB-backed against the **mocked** cobbler
connection; `MockConnection` implements `get_item_resolved_value`
(`MockConnection.java:258`), so resolved-options behaviour is exercisable
without a live cobbler.

## 2. Decisions locked in this round (planning round 5)

1. **Steps 1.3 and 1.4 are implemented in one session** (user decision):
   both consume the same builder wiring, and the shared helpers land here.
2. **Session tracking for every OS.** The install-source override moves into
   the builders via a new contract method `mediaOptions(String mediaUrl)`
   with per-OS-class keys — `install=` (AutoYaST), `inst.repo=` (RHEL /
   fedora / generic — new: enables RHEL session tracking), `inst.install_url=`
   (Agama, product-default-override semantics per §1.7). No breed gate.
3. **`self_update` stays on the system record** via
   `builder.selfUpdateOption(tree)` with the **schedule user**: schedule-time
   re-evaluation is required because custom installer-updates child channels
   are org/user-tied, while a vendor/global lookup would only see channels
   available to everyone. The resolved opt-out is honored in **both**
   `self_update=0` and `inst.self_update=0` forms; user-typed keys win.
4. **Generic gating:** the suse-breed check is dropped; dispatch is by
   install type through the factory. `ksData == null` guards the whole block
   (finding 1.4 — the guard state is unreachable otherwise, documented).
5. **Helpers live in one standalone class** `KernelOptionsHelper` next to the
   builders (`com.suse.manager.autoinstallation.builder`): the SUSE
   `text`→`textmode` transform and `cobblerSystemNopxeUrl`. The existing URL
   helpers stay in `AbstractKernelOptionsBuilder` (no step-1.1 churn).
6. **RHEL `systemOptions` keeps `inst.ks.sendmac inst.ks=`** (user decision,
   finding 1.8); the spec and roadmap changelog are corrected, no code change.
7. **Initiate-path cleanup per locked step-1.1 decisions:** the debian/ubuntu
   branch and the rhel6 `kssendmac` variant are dropped; routing failures
   (`KernelOptionsBuilderException`) propagate.
8. **Cobbler records stay free of installer-selection options**
   (`profileOptions` empty until G6; `systemOptions` only for boot lines
   Uyuni composes itself) — unchanged G6 flip-switch stance.

## 3. Code design

### 3.1 `KernelOptionsBuilder` — new contract method

```java
/**
 * Installation-source override pointing at the session-tracked media
 * URL built by the caller as http://<kickstartHost><mediaPath>.
 * The option key is OS-class specific: install= (AutoYaST),
 * inst.repo= (RHEL-like), inst.install_url= (Agama product-default
 * override). Idempotent add-if-absent; a user-typed option on the
 * schedule form wins.
 */
KernelOptionsList mediaOptions(String mediaUrl);
```

### 3.2 Builder implementations (one line each, `setOptionIfNotPresent`)

| Builder | Key |
|---|---|
| `AutoYastKernelOptionsBuilder` | `install` |
| `RhelKernelOptionsBuilder` | `inst.repo` |
| `AgamaKernelOptionsBuilder` | `inst.install_url` |

### 3.3 `KernelOptionsHelper` (new, `com.suse.manager.autoinstallation.builder`)

```java
public final class KernelOptionsHelper {
    private KernelOptionsHelper() { }

    /** SUSE boot lines use textmode; plain text is not a SUSE option. */
    public static void applySuseTextmode(KernelOptionsList options) {
        if (options.hasOption("textmode")) {
            options.removeOption("text");
        }
        else if (options.hasOption("text")) {
            options.removeOption("text");
            options.setOption("textmode", "1", true);
        }
    }

    /** http://<fqdn>/cblr/svc/op/nopxe/system/<systemName> */
    public static String cobblerSystemNopxeUrl(String serverFqdn, SystemRecord system) {
        // null system -> KernelOptionsBuilderException, as in the builders
    }
}
```

### 3.4 System path — `CobblerSystemCreateCommand#processKernelOptions`

```java
private void processKernelOptions(Profile recProfile) {
    if (recProfile == null || kernelOptions == null || kickstartHost == null ||
            mediaPath == null || ksData == null) {
        return;
    }
    KernelOptionsList options = new KernelOptionsList(kernelOptions);
    KernelOptionsBuilder builder = KernelOptionsBuilderFactory
            .getBuilder(ksData.getInstallType(), kickstartHost, user);
    options.addMissingOptions(builder.mediaOptions("http://" + kickstartHost + mediaPath));
    KernelOptionsList resolved = new KernelOptionsList(recProfile.getResolvedKernelOptions());
    if (!(resolved.hasOption("self_update", "0") || resolved.hasOption("inst.self_update", "0"))) {
        options.addMissingOptions(builder.selfUpdateOption(ksData.getTree()));
    }
    kernelOptions = options.toString();
}
```

* Parsed-key checks replace the substring `contains()` guards.
* `processOptionalProperties` unchanged (`kernelOptions` + raw
  `kernelOptionsPost` onto the record); no caller signature changes.
* Routing failures propagate (decision 7 stance), unreachable for existing
  `rhnKSInstallType` labels.

### 3.5 Initiate path — `KickstartInitiateAction`

`prepareCobblerBoot` passes the already-resolved tree down;
`buildKernelOptions(SystemRecord, KickstartableTree, String host, boolean autoinstall)`
becomes:

```java
KernelOptionsList kopts = new KernelOptionsList(sys.getResolvedKernelOptions());
if (sys.getProfile().getDistro().getBreed().equals("suse")) {
    KernelOptionsHelper.applySuseTextmode(kopts);
}
kopts.removeOption("initrd");
KernelOptionsBuilder builder =
        KernelOptionsBuilderFactory.getBuilder(tree.getInstallType(), host);
kopts.addMissingOptions(builder.systemOptions(sys));
if (autoinstall) {
    kopts.addOption("info", KernelOptionsHelper.cobblerSystemNopxeUrl(host, sys));
}
return kopts.toString();
```

* The breed check remains **only** for the textmode transform (spec §5.3:
  applied for suse breed) — a distro fact, not a routing decision; routing is
  install-type-only.
* The upgrade-detection in `getSaltCalls`
  (`kOpts.contains("autoupgrade=1") || kOpts.contains("uyuni_keep_saltkey=1")`)
  keeps working on the serialized string.
* The hand-rolled map serializer (`convertOptionsMap`) and the debian/ubuntu
  + rhel6 branches are removed.

No schema, UI/JSP, Struts or XML-RPC API changes.

## 4. Behavior deltas (parity register)

| Delta | Reason / notes |
|---|---|
| SLE16 system records get `inst.self_update=` instead of `self_update=` | Bug fix (finding 1.5); Agama consumes the `inst.` form. |
| Resolved opt-out also honored as `inst.self_update=0` | Finding 1.5; the YaST-form-only check missed Agama-form opt-outs. |
| RHEL/fedora/generic system records get `inst.repo=<tinyurl>` | New: session tracking for RHEL-family installs (decision 2; user request). Legacy added nothing. |
| Agama system records get `inst.install_url=<tinyurl>` instead of an inert `install=` | Decision 2 + finding 1.7. Note: overrides the product default `installation_url` for all products. |
| Parsed-key checks replace substring `contains()` | No false suppression (e.g. `inst.self_update=` no longer suppresses `self_update=` adds and vice versa). |
| `ksData == null` skips the whole block incl. the media override | Unreachable state (finding 1.4); simplifies the gate. |
| Initiate RHEL: `inst.ks.sendmac inst.ks=<url>` (was `... ks=<url>`) | Locked user decision (finding 1.8); rhel6 `kssendmac` variant dropped (locked step-1.1 decision). |
| Initiate: no duplicate installer-selection option when the user typed one in profile options | `systemOptions` is add-if-absent; legacy appended a second (duplicate-key) occurrence. |
| Initiate: whitespace normalization of the serialized options | `KernelOptionsList` serializer replaces the hand-rolled one (legacy trailing-space quirk removed). |
| Scheduling flows without `mediaPath` (SSM create, wizard create, reactivation) remain no-ops | Parity preserved (finding 1.1.3). |

## 5. Test plan

### 5.1 DB-free (run locally in the sandbox)

* Builder `mediaOptions` tests: AutoYaST → `install=`, RHEL → `inst.repo=`,
  Agama → `inst.install_url=`; add-if-absent idempotency; composed with
  `selfUpdateOption` where applicable.
* `KernelOptionsHelperTest`: `applySuseTextmode` (textmode present → `text`
  dropped; `text` present → `textmode=1`; neither → untouched),
  `cobblerSystemNopxeUrl` (URL form, null system → exception).
* Existing builder/factory/list suites stay green (RHEL `systemOptions` test
  already asserts `inst.ks=`).

### 5.2 DB-backed (agent VM / CI)

`CobblerCommandTest` / `CobblerSystemCreateCommandTest` additions (mocked
cobbler, PostgreSQL per the established fixture):

* **SLES15:** mediaPath set → record carries
  `install=http://<host><mediaPath>` plus `self_update=<child URL | 0>`;
  `ksdevice=` + user options preserved; user-typed `install=`/`self_update=`
  wins over the computed ones.
* **SLE16:** record carries `inst.self_update=` (not `self_update=`);
  opt-out respected via resolved `inst.self_update=0` and via user-typed key.
* **RHEL:** record carries `inst.repo=http://<host><mediaPath>`.
* **No mediaPath → no-op**; **post options verbatim**; **store-twice
  idempotency**.
* `KickstartInitiateAction` kopts test: composed string for sles15
  (`autoyast=`, textmode transform), sle16 (`inst.auto=`), rhel
  (`inst.ks.sendmac inst.ks=`) over mock cobbler records with a resolved
  options map (`text`, `initrd` handling). Exact mechanics (direct call vs
  `getSaltCalls`) settled while implementing.

### 5.3 Style gates (local sandbox)

```
mvn -f java compile checkstyle:checkstyle
```

## 6. Verification on the agent VM

Same protocol as steps 1.2/1.5 — single shared VM, record the usage window
and deployed package versions in
`agents-workbench/autoinstallation/session-log.md`.

1. Check `podman ps` and `podman exec uyuni-server rpm -q spacewalk-java`
   before/after; when the running Uyuni is not needed: `mgradm stop` (frees
   port 5432).
2. Test DB per `java/Makefile.docker` flow (`uyuni-master-pgsql-4eclipse`
   container, worktree mounted at `/manager`, branch schema incl.
   `opensuse16.1`).
3. Run: `mvn -f java/core test -DskipTests=false
   -Dtest='CobblerCommandTest,CobblerSystemCreateCommandTest,KernelOptions*Test,*KernelOptionsBuilder*Test,KickstartInitiate*Test'`
   with `JAVA_HOME` at JDK 17.
4. Afterwards: remove the test container, `mgradm start`, record results.

Full PXE/UI E2E regression stays with step 1.7 (mind the cobbler PR #177
caveat, `00-goals.md` §5.1).

## 7. Documentation updates (implementation session)

* `10-roadmap.md`: steps 1.3/1.4 → DONE + changelog entry (incl. the
  step-1.2 changelog correction from §1.8).
* `20-kernel-options.md`: §2 — add `mediaOptions` to the contract table, fix
  the RHEL `systemOptions` description to `inst.ks.sendmac` + `inst.ks=`;
  §5.2 — rewrite for the generic system path; §5.3 — rewrite for the builder
  -composed initiate path (normalizations, helper class, `info=` helper).
* `40-step-1.2-distro-path.md`: changelog note superseding the §9 `ks=`
  repair claim (the `inst.ks=` form is the locked correct one).
* Regenerate the presentation HTMLs: `.venv/bin/python autoinst_plan/build_html.py`.
* Session log entry in `agents-workbench/autoinstallation/session-log.md`.

## 8. Out of scope

* `KickstartScheduleCommand#getExtraOptions` user-part resolution — step 2.6
  (the `ksdevice=` + custom options prepend is preserved verbatim).
* Wizard/SSM option resolution — dies with the wizard (step 2.6).
* Saltboot PXE option maps — step 3.4.
* `CobblerDistroSyncCommand#backsyncKernelOptions` — Phase 1 deferral stands.
* Enabling `profileOptions` — G6 flip-switch (decision 8).
* Proxy `tftp_wrapper.py` changes — step 5.3 contract; the wrapper works
  as-is with the formats produced here (finding 1.3).
* RHEL media tracking for profiles that pin their own repo URLs — profile
  content, not addressable in the builders.

## 9. Implementation notes (2026-10-09)

* Implementation followed §3. `mediaOptions` added to the contract and the
  three builders (`install=` / `inst.repo=` / `inst.install_url=`),
  `KernelOptionsHelper` created with `applySuseTextmode` +
  `cobblerSystemNopxeUrl`, `CobblerSystemCreateCommand#processKernelOptions`
  rewritten generically (no breed gate), `KickstartInitiateAction` composes
  via the factory-routed builders.
* **`KernelOptionsList#getValue(String)` added** — the step-1.1 starting
  -point description (spec §1) listed `getValue` but it never existed; added
  as the first-value accessor (used by the new tests).
* **`MockConnection#get_item_resolved_value` repaired** — the previous
  implementation could never match (it searched by the attribute key as if
  it were a uid and returned `dataMapResolved.get("uuid")`), i.e. resolved
  getters always returned `null` under the mock. It now resolves the
  requested attribute with a proper inheritance merge
  (system → profile → distro, lower objects win, string values parsed like
  cobbler parses options strings) and a visited-set guard. The guard proved
  necessary immediately: the saltboot fixtures create a **profile named
  exactly like its distro** (`SaltbootUtils.makeCobblerName(org,
  BOOT_IMAGE)` for both), and the mock's cross-collection `findByName`
  made that profile its own parent → `StackOverflowError`.
* **`SystemHandlerProvisioningTest#testProvisionSystem` expectation updated:**
  the provisioned RHEL-family system record now carries
  `console=tty0 inst.repo=http://localhost/ty/<token>` (session-tracked
  media override — the intended behavior change of this step) instead of the
  legacy raw user options; the leading-space artifact is gone
  (normalization). Post options still verbatim.
* Test mechanics: the mock stores kernel options strings raw (established
  behavior the step-1.5 assertions rely on), so the new tests bridge with
  `String.valueOf(rec.getKernelOptions().get())` / `KernelOptionsList`
  parsing instead of `.get()` typing. The initiate-path assertions compare
  **sorted option tokens** — the resolved map is a `HashMap`, so both the
  legacy serializer and `KernelOptionsList(Map)` have no stable order.
* Checkstyle: `mvn -f java compile checkstyle:checkstyle` clean (no ant).
* Verification: local sandbox — 49 DB-free tests green (builders incl. the
  new `mediaOptions` cases, `KernelOptionsHelperTest`, list, factory) +
  compile/checkstyle clean. Agent VM window 10:20–11:00 CEST 2026-10-09:
  `mgradm stop`, `uyuni-test-pgsql` container (worktree mounted at
  `/manager:Z` — SELinux is enforcing on the VM, the plain mount is not
  readable from containers; branch schema incl. `opensuse16.1` verified),
  JDK 17. Results: **126 tests green** — 16 `CobblerSystemCreateCommandTest`
  (incl. 10 new system-path cases: SLES15 with/without installer-updates
  child, SLE16 `inst.self_update=`, both resolved opt-out forms, user-typed
  wins, RHEL `inst.repo=`, no-mediaPath no-op, post verbatim, store-twice
  idempotency), 16 `CobblerCommandTest` (regression), 4
  `KickstartInitiateActionTest` (SLES15/SLE16/RHEL kopts, no-info variant),
  11 tree-op, 28 builder/factory/list, and the mock-using regression suites
  (`SystemHandlerProvisioningTest` 3, `PXEEventTest` 15, `SaltbootUtilsTest`
  11, `SaltbootMigrationUtilsTest` 7, distro/profile/network/image, task/
  action suites) — plus a second window batch: 109 regression tests green.
  Test container removed, `mgradm start` — deployed stack healthy,
  `spacewalk-java-5.3.1-260400.1.2.uyuni6.noarch` (unchanged, no
  deployment). E2E PXE/UI matrix stays with step 1.7 (mind the cobbler
  PR #177 caveat for SLE16 PXE).

## Changelog

* 2026-10-09 — created from planning round 5: steps 1.3 + 1.4 combined;
  per-OS `mediaOptions` contract method (`install=` / `inst.repo=` /
  `inst.install_url=`) enabling session tracking for every OS; `self_update`
  retained on the system record with schedule-user re-evaluation; generic
  (install-type) gating replacing the suse-breed check; standalone
  `KernelOptionsHelper` (textmode transform + nopxe URL); RHEL `inst.ks=`
  docs correction; parity register and test plan.
* 2026-10-09 — implemented and verified: 126 + 109 tests green on the agent
  VM, checkstyle clean. Mock `get_item_resolved_value` repaired (was dead),
  `KernelOptionsList#getValue` added, one legacy-expectation test updated
  (`SystemHandlerProvisioningTest`). Status → DONE.
