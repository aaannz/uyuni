# Step 1.6 Design — Tests (builder suite completion + real-cobbler option)

> Implementation design for roadmap step 1.6 (`10-roadmap.md` Phase 1). The
> behavioural specification remains [`20-kernel-options.md`](20-kernel-options.md)
> (§7 test outline, §9 test-infrastructure notes); this document fixes the
> remaining test-content plan and the design of the opt-in **real-cobbler test
> mode**, and records the planning-round-6 decisions.

| | |
|---|---|
| Branch | `sles16_agama` |
| Created | 2026-10-09 |
| Status | DONE (2026-10-09) |
| Related | `00-goals.md` §G0/§5.1, `10-roadmap.md` step 1.6, `20-kernel-options.md` §7/§9, step docs 30/40/50/60 |

---

## 1. Research findings (verified against code and the agent VM, 2026-10-09)

### 1.1 Test-content inventory (what steps 1.1–1.5 already cover)

The §7 outline is largely implemented as a by-product of steps 1.1–1.5:

* **Pure-logic:** per-builder `distroOptions`/`selfUpdateOption`/`mediaOptions`
  /`profileOptions`/`systemOptions` for all three builders
  (`AgamaKernelOptionsBuilderTest`, `AutoYastKernelOptionsBuilderTest`,
  `RhelKernelOptionsBuilderTest` — jMock + `ByteBuddyClassImposteriser`, DB-free);
  `KernelOptionsHelperTest` (text→textmode, nopxe URL);
  `KernelOptionsBuilderFactoryTest` (routing incl. SLE16-before-SUSE
  precedence, unknown-type/null-type/FQDN failures); `KernelOptionsListTest`
  (container semantics).
* **Call-site parity (DB-backed):** tree create
  (`TreeCreateOperationTest`: SLES15/SLE16/RHEL + user-wins + idempotency),
  system create (`CobblerSystemCreateCommandTest`: exact SLES15 string, parsed
  SLE16/RHEL, opt-outs in both forms, user-wins, post-options raw,
  store-twice idempotency), initiate (`KickstartInitiateActionTest`: sorted
  tokens per OS class, textmode, `initrd` removal, `info=` gating),
  profile edit (`CobblerCommandTest`: normalization, quoting, `<<inherit>>`/
  empty round-trip, idempotency, post raw, null-skip), full provisioning flow
  (`SystemHandlerProvisioningTest` media override).

Remaining gaps (this step's B-scope, §4 below):

1. `AbstractKernelOptionsBuilder` has no dedicated test; the **org-global
   fallback branch** (`ChannelFactory.listAllChildrenForChannel`,
   `AbstractKernelOptionsBuilder.java:80` — the §5.5 "no user → org-global"
   half) is never exercised, nor are the URL helpers directly.
2. `KickstartHostHelper` (step 1.5) has no tests: canonicalization,
   null/empty → `ConfigDefaults.getJavaHostname()` fallback,
   `UnknownHostException` fallback are untested.
3. `KickstartInstallType` predicates `isSUSE`/`isRhel`/`isFedora`/`isGeneric`
   are only covered transitively via factory routing;
   `getCobblerBreed()`/`getCobblerOsVersion()` for `opensuse16.1` are never
   asserted (spec §5.1 "cobbler breed `suse`" is a locked decision).
4. Exact-string parity exists only for SLES15 in the system path
   (`CobblerSystemCreateCommandTest#testSles15SystemKernelOptions`); SLE16 and
   RHEL system records are asserted parsed-key style only.
5. `RhelKernelOptionsBuilder` lacks a merge/user-wins/idempotency case (the
   other two builders have `testMergeIsUserWinsAndIdempotent`); the Agama
   builder lacks the "ignores non-installer-updates children" case.
6. `KernelOptionsList` API holes: `getValue`, `hasOption(key, value)`,
   `setFlagIfNotPresent` have no dedicated tests.
7. Tree **edit**/XML-RPC-update recompute (step 1.2's core fix) is only
   covered at tree create — `TreeEditOperationTest` has no kernel-options
   assertions.
8. Schedule-user scoping negative case: no test where the installer-updates
   child exists but is **not accessible** to the schedule user (system path).
9. Initiate-path idempotency (re-compose over an already-composed line) and
   the RHEL "raw `text` survives untransformed" case are untested.

### 1.2 Test-infrastructure facts (code)

1. `KickstartTestUtils.setupTestConfiguration(User)` is the single config
   seam: it maps the `CobblerXMLRPCHelper` config key to `MockXMLRPCInvoker`
   and `org.cobbler.CobblerConnection` to `MockConnection`
   (`KickstartTestUtils.java:35–62`), clears the mock, and sets the
   `/tmp/kickstart` mount points. Every cobbler-touching suite calls it.
2. `CobblerCommandTestBase(boolean connectToCobbler)` already implements the
   real-cobbler config swap (`CobblerCommandTestBase.java:72–76` — re-points
   both config keys to the real classes) but is **dead code**: no test uses
   `super(true)`. The connection class is resolved via
   `MethodUtil.getClassFromConfig` (`CobblerXMLRPCHelper.getConnection`,
   `CobblerCommand` constructor), so the swap affects the commands themselves,
   not just test clients.
3. Test fixtures live under host paths `/tmp/kickstart/...`
   (`KickstartableTreeTest.KICKSTART_TREE_PATH = /tmp/kickstart/images`) and
   `/tmp/opt/repo/...` (`CobblerCommandTestBase#ksdata` base path); cobbler
   validates the kernel/initrd paths of a distro at save time, so a real
   cobbler must see the same filesystem.
4. Tests reuse a **fixed MAC** (`NetworkInterfaceTest.TEST_MAC`); real cobbler
   rejects duplicate MACs across system records — per-test cleanup of real
   cobbler objects is therefore mandatory, not just hygiene.
5. No mockito in the test classpath (jMock + ByteBuddy only, `java/core/pom.xml:492–508`);
   static calls (`ChannelFactory.listAllChildrenForChannel`) cannot be
   jMock-imposterised.
6. The `java-unittests-pgsql.sh` automation flow
   (`susemanager-utils/testing/automation/` → `java/scripts/docker-testing-pgsql.sh`)
   never starts cobblerd and runs the ant targets with the mock
   `rhn.conf.example` — it is a **mock-only** flow (cobbler is installed in
   the image but not running and never contacted there). Cobbler being
   installed in the test image does not mean it is used.

### 1.3 Real-cobbler findings (dry-runs against ephemeral
`uyuni-master-pgsql-4eclipse` containers on the agent VM, 2026-10-09)

1. The test image contains **cobbler 3.3.3** (uyuni fork,
   `cobbler-3.3.3-260400.41.1.uyuni6`), apache2 (+prefork, mod_wsgi), the
   `authentication/spacewalk.py` module and `cobblerd`. Storage is
   file-based (`/var/lib/cobbler/collections`) — **no mongodb needed**.
   Tooling in the image: JDK 17 + ant; **no maven** (tests run via maven on
   the VM host, as in previous steps).
2. Java reaches cobbler through `http://<cobbler.host>:<cobbler.port>/cobbler_api`
   (`ConfigDefaults.getCobblerServerUrl`, default port 80). Apache proxies
   `/cobbler_api` → `http://127.0.0.1:25151/` (`01-cobblerapi.conf`
   ProxyPass); **cobblerd itself is path-sensitive** — XML-RPC POSTs to
   `/cobbler_api` on :25151 return 404, only `/` works. Apache is required.
3. Apache needs two fixes inside the container: `vhost-ssl.conf` aborts
   startup (`SSLCertificateFile /etc/pki/tls/certs/spacewalk.crt` missing — a
   deployment-time artifact; move the file aside) and startup is
   `/usr/sbin/start_apache2 -k start` (no `apache2ctl` in the image).
4. **Auth is the real work.** The image ships
   `module = authentication.configfile` in `/etc/cobbler/modules.conf`
   (digest user `cobbler`/`cobbler` in `users.digest`), but the Java side
   hard-codes the *spacewalk module* protocol: `IntegrationService.authorize`
   logs into cobbler with `taskomatic_user` + `web.session_secret_1`
   (automated user) or a SessionSwap-encoded random (regular users); cobbler's
   `authentication.spacewalk` then validates **by calling back into a Uyuni
   XML-RPC API** at `uyuni_authentication_endpoint` (`auth.checkAuthToken`
   for token-looking passwords, else `auth.login` + `user.listRoles`).
   Without a running Uyuni server no credential can validate.
   Solution that preserves production semantics: run a tiny **accept-all
   XML-RPC shim** inside the test container implementing exactly the three
   calls the module makes (`auth.checkAuthToken` → 1, `auth.login` → token,
   `user.listRoles` → `["org_admin"]`), switch the module to
   `authentication.spacewalk`, and point
   `uyuni_authentication_endpoint: http://127.0.0.1:8000` at the shim. The
   whole Java auth flow (token generation, `CobblerLoginCommand`,
   token re-check) then works unmodified.
5. Cobbler settings include from `settings.d/` glob **`*.settings` only**
   (`cobbler/settings/__init__.py:103`). The shipped
   `settings.d/zz-uyuni.conf` is never loaded (verified:
   `get_settings` returned `''` for the endpoint key) — the recipe writes its
   own `zz-uyuni.settings`.
6. `get_item_resolved_value` takes **`(uid, attribute)` — no token**
   (real cobbler 3.3.3 raises "takes 3 positional arguments but 4 were
   given" with a token appended). This matches the Java call
   (`CobblerObject.java:361` invokes it via `invokeMethod`, not
   `invokeTokenMethod`) and the step-1.3 mock repair.
7. Kernel/initrd files: distro save validates the paths and copies the files
   into `/srv/www/cobbler/images/`. Bind-mounting the host fixture dirs into
   the container makes them visible: `-v /tmp/kickstart:/tmp/kickstart:Z -v
   /tmp/opt:/tmp/opt:Z`. SELinux enforcing → `:Z` required; **podman refuses
   to relabel `/tmp` itself** ("SELinux relabeling of /tmp is not allowed") —
   only the subdirectories can be mounted.
8. Full round-trip validated inside an ephemeral container: shim + module
   switch → `login("taskomatic_user", <session secret>)` returns a token →
   distro create/modify/save (kernel/initrd from the host-mounted fixture
   dir) → profile create/modify/save → `get_item_resolved_value` →
   `remove_*` cleanup.
9. **Real cobbler rejects any token it did not issue itself.** Per-call
   validation goes `check_access` → `get_user_from_token`
   (`cobbler/remote.py:563–564`): `if token not in self.token_cache: raise
   CX("invalid token")` — the cache is only populated by successful `login()`
   calls. This rules out the "keep the login mocked, swap only
   `CobblerConnection` to real" alternative: every CRUD call would die with
   `CX: invalid token`. A real login through the `authentication.spacewalk`
   callback protocol (i.e. the shim) is therefore mandatory for the
   real-cobbler mode.

## 2. Decisions locked in this round (planning round 6)

1. **Activation by system property + assumption.** `-Duse.real.cobbler=true`
   switches the phase-1 DB-backed suites to a real cobbler; a cheap
   availability probe (TCP connect with 3s timeout to `cobbler.host:cobbler.port`)
   plus JUnit `assumeTrue` skips the tests when cobbler is absent. No
   auto-detection and no silent mode changes: unset property ⇒ byte-identical
   behaviour to today (mock), CI unaffected.
2. **Scope: the phase-1 suites only** — `CobblerCommandTestBase` (profile
   tests), `CobblerSystemCreateCommandTest`, `KickstartInitiateActionTest`,
   `TreeOperationTestBase`. Other `setupTestConfiguration` callers stay
   mock-only for now; extending them later is a one-liner each.
3. **Per-test cleanup in real mode is part of the contract** (decision 1.2-4:
   fixed MAC duplicates; fresh cobbler state per test). Mock mode keeps
   `MockConnection.clear()`.
4. **The container setup script and shim are committed** to
   `java/buildconf/test/cobbler/` (next to the test `rhn.conf` sources) —
   reproducible, reviewable, idempotent.
5. **Minimal testability seam in `AbstractKernelOptionsBuilder`**: the
   org-global branch moves behind a protected
   `listAllChildren(Channel)` hook (delegating to
   `ChannelFactory.listAllChildrenForChannel`), overridable in the DB-free
   test. No behavior change; no new mocking dependency (mockito is not on the
   classpath and jMock cannot intercept statics).
6. **Real-cobbler deltas are findings, not relaxations.** The mock must
   mirror real cobbler (it is the unit-test oracle for CI). Any mismatch
   surfacing in real mode (attribute aliases, resolved-value semantics,
   `<<inherit>>` handling) gets investigated and documented — potentially a
   mock/product fix — before the parity tests are allowed to assert it.

## 3. Design A — real-cobbler test infrastructure

### 3.1 Container side (`java/buildconf/test/cobbler/`)

**`cobbler-auth-shim.py`** — the validated shim (single file, stdlib only):

```python
from xmlrpc.server import SimpleXMLRPCServer, SimpleXMLRPCRequestHandler

class Handler(SimpleXMLRPCRequestHandler):
    rpc_paths = ("/rpc/api",)

def checkAuthToken(user, token):        # accept-all: test-only trust boundary
    return 1
def login(user, password):
    return "shim-session-token-0001"
def listRoles(session, user):
    return ["org_admin"]

server = SimpleXMLRPCServer(("127.0.0.1", 8000), requestHandler=Handler,
                            logRequests=False, allow_none=True)
server.register_function(checkAuthToken, "auth.checkAuthToken")  # dotted names,
server.register_function(login, "auth.login")                    # as spacewalk.py
server.register_function(listRoles, "user.listRoles")            # calls them
server.serve_forever()
```

**`setup-cobbler-test.sh`** — idempotent (safe to re-run; waits for the
stack to answer), runs *inside* the container:

1. `sed` `/etc/cobbler/modules.conf`:
   `authentication.configfile` → `authentication.spacewalk`.
2. Write `/etc/cobbler/settings.d/zz-uyuni.settings`:
   `uyuni_authentication_endpoint: http://127.0.0.1:8000` +
   `redhat_management_permissive: true` + `manage_tftpd: false` (the
   login-fallback branch of the auth module needs the permissive flag; the
   tftpd management is not needed by the tests and its background sync work
   races with the per-distro file copies). Note: cobbler only loads
   `settings.d/*.settings`, not the shipped `zz-uyuni.conf`.
3. Disable `/etc/apache2/vhosts.d/vhost-ssl.conf` (missing deployment cert).
4. Link `/var/lib/cobbler/templates/{wizard,raw}` to the test kickstart dir
   (finding 8.1-3: cobbler validates the profile's template path there).
5. Expose `/cobbler_api` beyond localhost via
   `/etc/apache2/conf.d/zz-cobblerapi-test.conf` (the tests connect through
   the published port; same approach as the acceptance suite).
6. Apply the two container-only cobbler patches (finding 8.1-5) and restart
   cobblerd when running.
7. Start `cobbler-auth-shim.py` (background), wait for :8000.
8. Start `cobblerd` (background), wait for :25151.
9. Start `/usr/sbin/start_apache2 -k start` (or restart when running),
   wait for :80.
10. Smoke check: XML-RPC `login(taskomatic_user, <anything>)` against
    `http://127.0.0.1/cobbler_api` — exits non-zero on failure.
    Logs: `/tmp/cobbler-test-{shim,cobblerd,apache}.log`.

### 3.2 Test side (`java/core/src/test/java/...`)

**`KickstartTestUtils`** (`com.redhat.rhn.domain.kickstart`):

```java
public static final String USE_REAL_COBBLER_PROPERTY = "use.real.cobbler";

public static boolean useRealCobbler() {
    return Boolean.getBoolean(USE_REAL_COBBLER_PROPERTY);
}

/** Existing mock setup; delegates to the overload with useRealCobbler(). */
public static void setupTestConfiguration(User u) throws Exception {
    setupTestConfiguration(u, useRealCobbler());
}

public static void setupTestConfiguration(User u, boolean realCobbler)
        throws Exception {
    /* ... existing mock configuration unchanged ... */
    if (realCobbler) {
        assumeTrue("Cobbler not reachable", isCobblerReachable());
        // same swap CobblerCommandTestBase(true) always did:
        Config.get().setString(CobblerXMLRPCHelper.class.getName(),
                CobblerXMLRPCHelper.class.getName());
        Config.get().setString(CobblerConnection.class.getName(),
                CobblerConnection.class.getName());
    }
    /* ... mount-point dirs, MockConnection.clear() stays (harmless) ... */
}

private static boolean isCobblerReachable() {
    // Socket.connect(new InetSocketAddress(host, port), 3000) against
    // ConfigDefaults.get().getCobblerServerUrl(); false on any IOException
}

/** Teardown: real cobbler -> wipe collections (systems first: dependency
 *  order); mock -> clear the static state. */
public static void cleanUpCobbler() {
    if (useRealCobbler()) {
        CobblerConnection con = CobblerXMLRPCHelper.getUncachedAutomatedConnection();
        SystemRecord.list(con).forEach(CobblerObject::remove);
        Profile.list(con).forEach(CobblerObject::remove);
        Distro.list(con).forEach(CobblerObject::remove);
    }
    else {
        MockConnection.clear();
    }
}
```

* `forEach(CobblerObject::remove)` uses the instance `CobblerObject.remove()`
  (`CobblerObject.java:393`); `SystemRecord.list`/`Profile.list`/
  `Distro.list(CobblerConnection)` all exist.
* Order systems → profiles → distros: real cobbler rejects removing a distro
  that profiles still reference.
* Cleanup runs in `@AfterEach` — before `BaseTestCase`'s `Config.clear()`
  (subclass `@AfterEach` methods run first in JUnit 5).
* Images are not cleaned: no phase-1 suite creates cobbler images
  (saltboot does, but it is out of this step's scope).

**Base wiring:**

* `CobblerCommandTestBase`: its `connectToCobbler` constructor flag is unified
  with the property — `real = connectToCobbler || KickstartTestUtils
  .useRealCobbler()` — and the inline config swap is replaced by
  `KickstartTestUtils.setupTestConfiguration(user, real)` (keeping the
  existing `TestUtils.commitAndCloseSession()` after the swap). Plus an
  `@AfterEach` calling `cleanUpCobbler()`.
* `CobblerSystemCreateCommandTest`, `KickstartInitiateActionTest`,
  `TreeOperationTestBase`: their existing
  `KickstartTestUtils.setupTestConfiguration(user)` calls become
  property-aware automatically; each gains an `@AfterEach` →
  `cleanUpCobbler()`.

### 3.3 Agent-VM run recipe

```
# on the VM (worktree rsync'd to ~/worktree/<branch>):
mgradm stop                                  # frees :5432 (and :80)
mkdir -p /tmp/kickstart /tmp/opt             # bind-mount sources
podman run -d --name uyuni-test-pgsql \
  -p 5432:5432 -p 8080:80 \
  -v ~/worktree/<branch>:/manager:Z \
  -v /tmp/kickstart:/tmp/kickstart:Z -v /tmp/opt:/tmp/opt:Z \
  registry.opensuse.org/systemsmanagement/uyuni/master/docker/containers/uyuni-master-pgsql-4eclipse

# test rhn.conf (local, gitignored):
echo "cobbler.port = 8080" >> java/buildconf/test/rhn.conf

# start the real cobbler inside the container:
podman exec uyuni-test-pgsql /manager/java/buildconf/test/cobbler/setup-cobbler-test.sh

# run (VM host, JAVA_HOME=/usr/lib64/jvm/java-17-openjdk):
mvn -f java/core test -DskipTests=false \
    -Dtest='CobblerCommandTest,CobblerSystemCreateCommandTest,KickstartInitiateActionTest,TreeCreateOperationTest,TreeEditOperationTest,TreeDeleteOperationTest' \
    -Duse.real.cobbler=true
```

Without `-Duse.real.cobbler=true` the same container serves the mock-mode
runs (the §9.3 regression map). The `cobbler.port=8080` line is inert in mock
mode and in CI (nothing connects).

## 4. Design B — remaining §7 test content

### 4.1 DB-free (run locally)

| File | New content |
|---|---|
| `AbstractKernelOptionsBuilderTest` (new, `...autoinstallation/builder/`) | URL helpers (`distTreeUrl`, `cobblerSystemAutoinstallUrl`) exact strings; `installerUpdatesValue` user-scoped branch (mocked `Channel#getAccessibleChildrenFor`, mirroring the existing builder tests); **org-global branch** via a test subclass overriding the new protected `listAllChildren` hook (decision 5); mixed-children filtering; `require` null-message shape. |
| `KickstartHostHelperTest` (new, `...manager/kickstart/`) | provided FQDN canonicalized; null/empty → `ConfigDefaults.getJavaHostname()` unchanged; unresolvable host (`*.invalid`) → input returned unchanged (`UnknownHostException` fallback). Needs only `Config`/`ConfigDefaults` — DB-free, local-runnable. |
| `KickstartInstallTypeTest` (extend) | direct `isSUSE`/`isRhel`/`isFedora`/`isGeneric` truth table incl. `opensuse16.1` (`isSUSE()` true, SLE16 routing precedes); `getCobblerBreed()` = `suse` and `getCobblerOsVersion()` for `opensuse16.1` (locked §5.1 identity). |
| `RhelKernelOptionsBuilderTest` (extend) | `testMergeIsUserWinsAndIdempotent` parity case (user `inst.repo=` wins; re-merge no-op). |
| `AgamaKernelOptionsBuilderTest` (extend) | mixed-children case (one installer-updates child among non-flagged children) — parity with AutoYaST. |
| `KernelOptionsListTest` (extend) | `getValue` (present/absent), `hasOption(key, value)`, `setFlagIfNotPresent` dedicated cases. |

### 4.2 DB-backed (agent VM; CI runs them with the mock)

| File | New content |
|---|---|
| `CobblerSystemCreateCommandTest` (extend) | exact-string parity for SLE16 (`inst.install_url=… inst.self_update=…`) and RHEL (`inst.repo=…`) with an empty profile-kopts fixture (deterministic option order — resolved maps are unordered, `20-kernel-options.md` §9.1); **schedule-user negative scoping**: installer-updates child exists but is not accessible to the schedule user → `self_update=0`/`inst.self_update=0` fallback. |
| `TreeEditOperationTest` (extend) | kernel-options recompute on **edit** and XML-RPC update (the step-1.2 fix): computed options re-appear after edit; user-typed options win; idempotent second store. Uses the `TreeOperationTestBase` SUSE-loader fixtures. |
| `KickstartInitiateActionTest` (extend) | idempotency: feeding a previously composed line back into `buildKernelOptions` yields the same option set; RHEL: a resolved `text` option survives untransformed (no textmode swap outside suse breed). |

Assertion style follows the established patterns (exact strings where order
is deterministic; parsed-key/sorted-token where resolved maps are involved).

## 5. Verification plan

1. **Local:** DB-free suites + `mvn -f java/core test -DskipTests=false
   -Dtest='<db-free classes>'`; `mvn -f java compile checkstyle:checkstyle`.
2. **Agent VM, mock mode:** §9.3 regression map (all listed suites) — guards
   against regressions from the config-seam refactor.
3. **Agent VM, real mode:** the four suites with `-Duse.real.cobbler=true`
   per §3.3. First real run doubles as the mock-vs-real parity audit
   (decision 6): any delta is triaged before relaxing anything.
4. Record VM window, container/versions and results in
   `agents-workbench/autoinstallation/session-log.md`.

## 6. Documentation updates

* `10-roadmap.md`: step 1.6 status + changelog entry (this round).
* `20-kernel-options.md` §9: the real-cobbler test mode (settings.d glob,
  shim auth, bind-mount constraints, `get_item_resolved_value` signature,
  port publishing) as test-infrastructure notes.
* `AGENTS_CUSTOM.md` §3: the run recipe (VM quirks section, following the
  established pattern of recording environment learnings there).
* Regenerate presentation HTMLs (`.venv/bin/python autoinst_plan/build_html.py`).

## 7. Out of scope

* Saltboot suites and any non-phase-1 `setupTestConfiguration` caller
  (decision 2 — later one-liners).
* Cobbler `Image` records cleanup (no phase-1 suite creates images).
* CI wiring of a real cobbler container (CI keeps the mock; the property
  stays opt-in).
* Step 1.7 (dead-code cleanup, E2E matrix) and the cobbler PR #177 PXE
  validation.
* Changing any production (`src/main`) behavior; the only main-tree change is
  the `listAllChildren` seam (§2 decision 5) and nothing else.

## 8. Implementation notes (2026-10-09)

Everything of §3/§4 was implemented as designed. The real-cobbler mode's
first full run surfaced additional mock-vs-real deltas (decision 6 audit);
all were resolved on the test/infrastructure side — no production behavior
change beyond the `listAllChildren` seam.

### 8.1 Real-cobbler findings (mock-vs-real audit)

1. **Kernel options come back as unordered maps.** The mock stores the raw
   string set via modify; real cobbler parses it into a dict and the XML-RPC
   response arrives as a Java `Map` (unordered). All parity assertions use
   sorted-token comparisons (`assertOptionTokens`/`assertKoptsEqual`
   helpers); the option order is not part of the contract.
2. **Blank option values round-trip as `~`.** Real cobbler serializes blank
   dict values through its YAML storage (`flag` → `{flag: null}` → read back
   as the string `"~"`). The assertion helpers normalize `=~` back to bare
   flags, so mock and real render identically. Legacy parity: the old
   hand-rolled serializer rendered `text=` for the mock-shaped empty value
   and production read real cobbler's `text=~` — both compositions are
   covered (`testRhelTextSurvivesUntransformed` expects `text=` on the mock
   and `text=~` on real cobbler).
3. **Cobbler validates the profile's autoinstall template path at
   `modify`-time** (`Invalid automatic installation template file location
   ... file not found`). The fixtures create the (empty) template file
   before setting the profile's `kickstart` field, and the container setup
   script links `/var/lib/cobbler/templates/{wizard,raw}` to the test
   kickstart dir (`/tmp/kickstart/...`), where the Java side writes.
4. **Cobbler validates distro kernel/initrd paths at save** (existence *and*
   the kernel filename pattern) — the initiate-path fixture's hardcoded
   `/tmp/kickstart/images/vmlinuz` never existed and had to point at real
   fixture files.
5. **Cobbler 3.3.3 defects, patched inside the test container only** (by
   `setup-cobbler-test.sh`, guarded by a `cbltest-patch` marker):
   * `utils.copyfile` calls `os.path.samefile(src, dst)` in its error path
     with a not-yet-existing destination — raises and masks the original
     copy error (hit whenever distro files are copied across devices, e.g.
     from the host-mounted fixture directory).
   * `collection.add`'s synchronous per-distro file copy races with the
     background full-sync tasks (triggered by the commands under test via
     `CobblerSyncCommand` → `background_sync`) whose `clean trees` step
     removes the target directories mid-copy. The patch tolerates the
     resulting `FileNotFoundError` — the tests never read the tftpboot/www
     copies.
6. **`opensuse16.1` is not in the VM cobbler's signature list** (the
   documented PR #177 caveat, `00-goals.md` §5.1): distro saves with that
   os_version are rejected. `KickstartTestUtils.cobblerSupportsSles16()`
   probes it once per JVM (create+remove a probe distro); the five SLE16
   fixtures that create cobbler distros skip via `assumeTrue` in real mode
   and still run in mock mode and CI.
7. **Misc API semantics**: `get_profiles` lists only *saved* profiles (the
   mock lists unsaved `new_profile` items); `new_profile` requires a distro
   reference at profile save; `get_profiles` returns an `XmlRpcArray`
   (cast to `List`, not `LinkedList`); `get_item_resolved_value(uid,
   attribute)` takes no token (already matched by the step-1.3 mock
   repair).
8. **`testLogin` simplified**: the role juggling (`addPermanentRole` +
   `save` + `reload`) tripped a Hibernate `rhnusergroup.current_members`
   flush quirk under real mode and is irrelevant for what the test asserts
   (the shim accepts all logins anyway).

### 8.2 Verification results

* Local sandbox: 66 DB-free tests green, `mvn -f java/core test` +
  `mvn -pl core checkstyle:checkstyle` clean.
* Agent VM (window 13:10–17:05 CEST, `mgradm stop` → dedicated
  `uyuni-test-pgsql` container from `uyuni-master-pgsql-4eclipse`, worktree
  at `/manager:Z`, fixture dirs `/tmp/kickstart` + `/tmp/opt` mounted `:Z`,
  API published on `:8080`):
  * Mock mode (§9.3 regression map + all builder/factory/list suites +
    `KickstartInstallTypeTest` + `KickstartHostHelperTest`):
    **161 tests green**.
  * Real mode (`-Duse.real.cobbler=true`, cobbler started inside the test
    container via `setup-cobbler-test.sh`): **55 tests, 50 green, 5 skipped
    (SLE16, finding 6), 0 failures**.
  * Deployed stack restored afterwards (`mgradm start`,
    `spacewalk-java-5.3.1-260400.1.2.uyuni6.noarch`,
    `cobbler-3.3.3-260400.41.1.uyuni6` — unchanged).

## Changelog

* 2026-10-09 — created from planning round 6: real-cobbler test mode
  (property + assumption, phase-1 scope, container scripts committed,
  mandatory per-test cleanup) designed from VM dry-run findings; remaining
  §7 gaps enumerated and planned; `AbstractKernelOptionsBuilder` test seam
  decision.
* 2026-10-09 — shim necessity verified after review feedback: the
  `java-unittests-pgsql.sh` flow is mock-only (no cobblerd started, no real
  cobbler contact — hence no auth needed there); real cobbler requires
  login-issued tokens (`remote.py` invalid-token check), ruling out the
  mocked-login alternative. Accept-all shim kept as the
  production-protocol-preserving solution (user decision).
* 2026-10-09 — implemented and verified: container scripts, config toggle
  with availability assumption, mandatory per-test cleanup, `listAllChildren`
  seam, all §7 gap tests (new: `AbstractKernelOptionsBuilderTest`,
  `KickstartHostHelperTest`; extended: install-type predicates/breed,
  RHEL merge, Agama mixed children, `KernelOptionsList` API holes, SLE16/RHEL
  exact system parity, schedule-user scoping, tree-edit recompute,
  initiate idempotency + RHEL text). Real-mode audit findings recorded in
  §8.1 (sorted-token assertions, `~` blank-value round-trip, template-path
  validation, two container-only cobbler patches, SLE16 probe + skips).
  161 mock tests + 50/55 real-mode tests green on the agent VM; checkstyle
  clean. Status → DONE.
