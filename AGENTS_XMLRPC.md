# XML-RPC API Notes (`AGENTS_XMLRPC.md`)

Practical notes for writing scripts that talk to the Uyuni server's XML-RPC API
(testing, security validation, automation against the deployed instance).
Read this before writing XML-RPC calls; it avoids several non-obvious traps.

Verified against Uyuni 2026.08 / `spacewalk-java-5.3.1` unless marked *(unchecked)*.

---

## 1. Endpoint, auth, and protocol essentials

- Endpoint: `https://192.168.200.201/rpc/api` (session-based handlers).
  Human-readable docs: `https://192.168.200.201/rhn/apidoc/index.jsp`.
- Every request **must** set `Content-Type: text/xml`. Without it the server
  answers `HTTP 200` with an **empty body** — this looks like a broken API but
  is only a missing header.
- The certificate is self-signed; use `curl -k` or
  `ssl._create_unverified_context()` in Python.
- Default admin credentials after a container deployment: `admin` / `admin`
  (also in the container environment: `podman exec uyuni-server env | grep -E '^(ADMIN_USER|ADMIN_PASS)'`).
- `auth.login(user, password)` returns the session key (string, e.g. `29x…`).
  Pass it as the first parameter of every subsequent call; end with
  `auth.logout(key)`.
- `api.getVersion()` returns the API version as a string (`29` on 2026.08).
- Calling an unknown method faults with
  `Could not find method: <name> in class: <HandlerClass>` — handy for
  discovering which handler backs a namespace.

## 2. Python harness

- Reusable starting point: `tasklist/validation/harness.py`:
  - `call(method, *args, label=...)` → dict with `status` (`ok`/`fault`/`error`),
    `faultCode`, `faultString`; logs one line per call.
  - `run(name, fn)` + `save_results(path)` → one JSON artifact per test script
    (`tasklist/validation/results_*.json`).
  - `b64(script)` helper for Base64 script bodies.
  - Feel free to update the harness if needed.
- Don't redeclare `RESULTS` in test scripts: `run()`/`save_results()` operate
  on the harness module global. A local `RESULTS = {}` shadow makes in-script
  checks read an empty dict while the saved JSON still looks fine.
- Fault shape: `xmlrpc.client.Fault` with `faultCode` (int) and `faultString`
  (usually prefixed `redstone.xmlrpc.XmlRpcFault:`). Unhandled Java exceptions
  surface as `faultCode -1` with `unhandled internal exception: …` — treat
  these as product bugs (stack-trace leakage), not as normal API errors.
- **Concurrency:** `xmlrpc.client.ServerProxy` is **not thread-safe**. For
  parallel calls create one proxy per thread (shared proxies fail with
  `CannotSendRequest` / `ResponseNotReady`). See `tasklist/validation/tests_f05_race.py`
  for the working pattern.
- `allow_none=True` is required by some calls, but the server does not handle
  `<nil/>` gracefully everywhere (passing `None` for a String parameter NPEs
  server-side instead of returning a clean fault).

## 3. Parameter types and current signatures (verified on 5.3.1)

- `$date` parameters must be real XML-RPC `dateTime.iso8601` values, i.e.
  `xmlrpc.client.DateTime()` in Python. ISO-8601 *strings* fail with
  `TranslationException: Could not find translator for class java.lang.String
  to class java.util.Date`. This includes `None` for an optional `$date` —
  e.g. `system.scheduleApplyHighstate(key, sid, date, test)` needs
  `xmlrpc.client.DateTime(time.gmtime())` even for immediate execution.
- Handler parameters typed as `Boolean` need real XML-RPC booleans (Python
  `True`/`False`). Integers fail with `Could not find translator for class
  java.lang.Integer to class java.lang.Boolean`.
- Signatures that differ from older Satellite / SUSE Manager documentation:
  - `org.create(key, orgName, adminLogin, adminPassword, prefix, firstName,
    lastName, email, usePamAuth)` — `prefix` (e.g. `"Mr."`) is required and
    `usePamAuth` must be boolean. Returns an org struct (with `id`), not an int.
  - `user.create(key, login, password, firstName, lastName, email[, usePamAuth])`
    — there is **no org parameter**; the user is created in the *caller's* org.
    To add a user to org N, first authenticate as an org-N admin. New users
    hold only the `regular_user` role.
  - Read-only users: `user.setReadOnly(key, login, true)`. `user.addRole(…,
    "read_only")` is rejected (`Role with the label [read_only] cannot be
    assigned/revoked …`).
  - `user.addAssignedSystemGroup(key, login, systemGroupName, setOfDefault)`
    takes the group *name*, not its id.
- Read-only users may call only `@ReadOnly`-annotated methods; anything else
  faults with `SecurityException: The <method> API is not available to
  read-only API users`.

## 4. Access-control model (useful for multi-tenant testing)

- Regular users see only systems in their assigned system groups; org admins
  see all systems of their org (implemented by the `rhnUserServerPerms` view:
  group-assigned systems ∪ org-admin orgs). A regular user without groups sees
  `system.listSystems → []`.
- Invisible and nonexistent systems return the same fault, `-208 "No such
  system"`.
- A user in a different org gets the same `-208` fault for foreign systems.
- Never rely on crash behavior (e.g. unhandled NPEs in some cross-org paths)
  as a security boundary — see the validation reports under `tasklist/` for
  examples where such crashes are the *only* barrier.

## 5. Namespace → handler discovery

- Authoritative mapping: `java/core/src/main/java/com/redhat/rhn/frontend/xmlrpc/HandlerFactory.java`
  (`factory.addHandler("<namespace>", handler)`).
- Non-obvious mappings: `systemgroup.*` → `ServerGroupHandler`
  (`systemgroup.create(key, name, description)`,
  `systemgroup.addOrRemoveSystems(key, name, sids, add)`).
- For a method's exact Java signature and intended parameter types, read the
  `@apidoc.param` javadoc block in the handler class — it is the source of
  truth for the apidoc and matches the runtime types.
- Full API documentation served live at `/rhn/apidoc/index.jsp`; API version
  via `api.getVersion()`.

## 6. Debugging API faults server-side

- Stack traces of unhandled exceptions land in `/var/log/rhn/rhn_web_ui.log`
  **inside the `uyuni-server` container** — not in `catalina.out` and not in
  the API log.
- `/var/log/rhn/rhn_web_api.log` logs one summary line per call
  (`CALL: <handler>.<method>(…)` with passwords masked) — useful to confirm
  which method/params actually arrived.
- Config changes in `/etc/rhn/rhn.conf` require
  `podman exec uyuni-server systemctl restart tomcat` (config values are read
  per-JVM); poll `api.getVersion` until Tomcat is back (~30–60 s).
- Before dynamic testing, confirm the deployed package matches your working
  branch: `podman exec uyuni-server rpm -q spacewalk-java` vs. local
  `java/pom.xml`.

## 7. Minimal working examples

curl:

```bash
curl -sk -X POST https://192.168.200.201/rpc/api \
  -H 'Content-Type: text/xml' \
  --data-binary '<?xml version="1.0"?><methodCall><methodName>api.getVersion</methodName><params></params></methodCall>'
```

Python:

```python
import ssl
import xmlrpc.client

client = xmlrpc.client.ServerProxy(
    "https://192.168.200.201/rpc/api",
    context=ssl._create_unverified_context(),
    allow_none=True,
)
key = client.auth.login("admin", "admin")
print(client.api.getVersion(), len(key))
client.auth.logout(key)
```

## 8. Items not yet verified (use with care)

- `api.*` namespace exposes further version/namespace introspection calls
  (`api.namespace.*`) *(unchecked)*.
- XML-RPC handlers for traditional clients behave differently from Salt
  minions in several system methods (e.g. locking is refused for Salt systems)
  *(verified for `system.setLockStatus` only)*.
- Java-side `Config.get()` may cache `rhn.conf` values beyond a Tomcat restart
  in some code paths — if a config change does not take effect, restart the
  whole container instead *(unchecked)*.
