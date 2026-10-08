# Agent Environment Guidelines (`AGENTS_CUSTOM.md`)

This document outlines the environment constraints, available tools, repository setup, and access to the isolated VM for tasks requiring containers or extended utilities.

---

## 1. Local Environment Constraints & Tooling

The primary local container/microvm environment is strictly sandboxed:

- **Build Tools Available Locally:**
  - **Java:** OpenJDK 17 (`/usr/bin/java`)
  - **Maven:** Apache Maven (`/usr/bin/mvn`)
  - **Python:** Python 3.13 (`/usr/bin/python3`)
  - **uv:** Fast Python package and virtual environment manager (`/usr/bin/uv`)

- **Python Virtual Environments:**
  - Agents must use `uv` to manage Python virtual environments.
  - Check for an existing `.venv` first. If absent, create one:
    ```bash
    uv venv .venv
    ```
  - Activate or run commands using the virtual environment:
    ```bash
    source .venv/bin/activate
    # or run commands directly:
    uv run <command>
    ```
  - Install dependencies into the virtual environment using `uv pip`:
    ```bash
    uv pip install <package>
    ```
  - *Note:* Do not use `/tmp`; use project-local `.venv` if temporary environments are needed.

- **Scratch Files:**
  - `/tmp/opencode` exists but is root-owned and not writable by the agent user.
  - Use project-local directories for scratch files, downloaded artifacts, and
    cookie jars (e.g. `tasklist/validation/artifacts/`).

- **Missing Local Utilities:**
  - **No Podman / Docker:** Container runtime engines are **not** present locally.
  - **No KVM / Virtualization:** KVM, QEMU, virsh, and virtualization utilities are **not** present locally.
  - For tasks requiring container engines, virtualization, or missing system tools, see [Section 3: Isolated VM](#3-isolated-vm-containers--system-testing).

---

## 2. Git Repository Activation

The local project directory is a linked git worktree. By default, `git` commands may fail with:
```
fatal: not a git repository: (null)
```
This occurs because the worktree pointer (`.git`) references host filesystem paths not directly exposed inside the sandbox.

### Activation Instructions

Whenever `git` does not recognize the repository, source the helper script in the current shell session:

```bash
source ~/workbase/gitactivate.sh
```

- **How it works:** Sourcing this script resolves the worktree to the read-only base repository mounted at `~/workbase/` and exports required environment variables (`GIT_COMMON_DIR`, `GIT_DIR`, `GIT_WORK_TREE`, and a writable index `GIT_INDEX_FILE`).
- **Subshell Usage:** If executing one-off bash commands or non-interactive subshells, chain the activation:
  ```bash
  source ~/workbase/gitactivate.sh && git status
  ```
- **Deactivation:** If needed to restore original environment variables, run:
  ```bash
  gitdeactivate
  ```

---

## 3. Isolated VM (Containers & System Testing)

When an agent needs **Podman**, package installation, services, or test environments not available in the local sandbox, an isolated VM is provided.

> **Single shared resource.** There is exactly **one** VM and it is
> **manually recycled**. If multiple agents (or subagents) work in parallel,
> they must coordinate VM usage — deployment and verification windows,
> snapshot/state assumptions — and record deployed package versions together
> with their results.

### VM Connection Details

- **IP Address:** `192.168.200.201`
- **User:** `root`
- **SSH Private Key:** `~/workbase/id_ssh_agents`

### Connecting via SSH

Always supply the private key and disable strict host key checking to prevent interactive prompts:

```bash
ssh -i ~/workbase/id_ssh_agents -o StrictHostKeyChecking=no root@192.168.200.201 "<command>"
```

For interactive sessions:
```bash
ssh -i ~/workbase/id_ssh_agents -o StrictHostKeyChecking=no root@192.168.200.201
```

### File Synchronization

To copy code, test artifacts, or files between the local sandbox and the VM, use `scp` or `rsync`:

```bash
# Copy file to VM
scp -i ~/workbase/id_ssh_agents -o StrictHostKeyChecking=no /path/to/local/file root@192.168.200.201:/path/to/destination/

# Sync directory using rsync
rsync -avz -e "ssh -i ~/workbase/id_ssh_agents -o StrictHostKeyChecking=no" /path/to/local/dir/ root@192.168.200.201:/path/to/destination/
```

### VM clock vs. workstation

A VM restored from a snapshot can run far behind the workstation. Check `date` on the VM (and `podman exec uyuni-server date`) and update the time and date of the VM.

### Deployment of the code using mvn

Deployment is integrated with the Maven deploy phase and is handled by the script java/scripts/maven/deploy.sh. Currently, only the deployment of the webapp is integrated into the build.

```bash
~/worktree/uyuni-$branch/java> mvn deploy -Ddeploy.host=192.168.200.201
```

The deployment script supports also deploying the frontend code and the salt files:

~/worktree/uyuni-$branch> npm --prefix web run build -- --check-spec=false
~/worktree/uyuni-$branch> java/scripts/maven/deploy.sh frontend --host 192.168.200.201

### Deployed Uyuni Instance

The VM runs a full Uyuni server in podman containers. Verify with `podman ps` —
`rpm -q spacewalk-java` on the VM host and `systemctl status spacewalk` are
misleadingly negative:

- Containers: `uyuni-server` (Uyuni 2026.08: tomcat, taskomatic, salt-master;
  systemd runs inside the container) and `uyuni-db` (PostgreSQL).
- Deployed version: `podman exec uyuni-server rpm -q spacewalk-java`
  (e.g. `spacewalk-java-5.3.1-...`). Match it against your working-branch
  version before any dynamic testing.
- Configuration: `/etc/rhn/rhn.conf` inside the `uyuni-server` container.
- Logs inside the container: `/var/log/rhn/` — `rhn_web_api.log` (API call
  summaries), `rhn_web_ui.log` (stack traces of unhandled API exceptions —
  not in `catalina.out`), `rhn_taskomatic_daemon.log`.
- Database: `podman exec uyuni-db psql -U spacewalk -d susemanager -c "<sql>"`.
  Credentials and the initial admin login are in the container environment:
  `podman exec uyuni-server env | grep -E '^(ADMIN|MANAGER|REPORT)'`
  (default `ADMIN_USER=admin` / `ADMIN_PASS=admin`).
- After changing `/etc/rhn/rhn.conf`, restart the API:
  `podman exec uyuni-server systemctl restart tomcat`; allow ~30–60 s and poll
  the API until it responds.
- The image ships a JRE only: no `jcmd`/`jstack`. For taskomatic thread
  evidence use DB tables instead (e.g. `rhnTaskoRun` start/end timestamps);
  `kill -3` output goes to the container log socket, not to a greppable file.

### Salt Minion Test Registration

To get a managed system for testing (e.g. script-run or highstate validation),
register a Salt minion on the VM host:

1. Install the bundled-Python minion from the Uyuni tools repo. It is immune
   to host Python drift and its salt version matches the container's master:
   ```bash
   zypper ar -f "https://download.opensuse.org/repositories/systemsmanagement:/Uyuni:/Main:/UyuniTools/openSUSE_Tumbleweed/" uyunitools
   zypper in -y venv-salt-minion
   ```
   The repo is reachable through the VM's network proxy (no blocked CDN
   redirects; rpm downloads may redirect to `ftp.gwdg.de`, which is allowed).
2. Point it at the salt master exposed by the `uyuni-server` container (ports
   4505/4506 are published on the VM host) and start the service. The
   venv-jailed minion ignores `/etc/salt/` entirely — its config root is
   `/etc/venv-salt-minion/`:
   ```bash
   mkdir -p /etc/venv-salt-minion/minion.d
   printf 'master: 127.0.0.1\nid: victim01\n' > /etc/venv-salt-minion/minion.d/victim.conf
   echo victim01 > /etc/venv-salt-minion/minion_id
   systemctl enable --now venv-salt-minion
   ```
3. Accept the key on the server and verify:
   ```bash
   podman exec uyuni-server salt-key -y -a victim01
   podman exec uyuni-server salt 'victim01' test.ping
   ```
   The first salt command inside the container can take minutes; use a
   generous timeout or run it in the background (`nohup … > /tmp/out 2>&1 &`)
   and poll the output file.
4. The system record appears in Uyuni within ~1–2 minutes; check via API
   `system.listSystems`.

Avoid two dead ends: the plain Tumbleweed `salt-minion` package crashes at
runtime (built for python3.14; workaround only: install `python313-salt` and
run `python3.13 -c 'from salt.scripts import salt_minion; salt_minion()'`), and
plain Leap-container minions fail on `zypper ref` because their repositories
redirect to `cdn.opensuse.org`, which the proxy blocks.

### Security Validation Task Conventions

For validating security-audit findings against the deployed instance:

- Workflow: statically verify every code/schema claim in the worktree first
  (grep line numbers, quoted snippets), then set up test tenants and a minion,
  then run dynamic tests, then write the report.
- Test-tenant pattern: org 2 via `org.create` (for cross-org testing);
  restricted same-org user via `user.create` + empty `systemgroup.create` +
  `user.addAssignedSystemGroup` — such a user sees no systems at all, which is
  the sharpest control for BOLA findings.
- Artifacts: test scripts and JSON results under `tasklist/validation/`
  (`harness.py` is a reusable XML-RPC harness); reports as
  `tasklist/<topic>-validation.md` + `tasklist/<topic>-validation.html`,
  matching the existing naming pattern.
- No cleanup after tests — the VM is recycled.
- **Caller's-org rule:** resource-creating API calls (`channel.software.create`,
  `errata.create`, `user.create`, ...) operate in the *caller's* org. To build
  org-2 fixtures, run `org.create`, then authenticate as the new org's admin and
  create its channels/errata from that session. A tenant channel created while
  logged in as the default admin silently lands in org 1 (base-channel arch
  label for software channels: `channel-x86_64`).
- **Scripted Web-UI login** (render checks, UI evidence): `GET
  /rhn/manager/login` returns the login page containing a hidden `csrf_token`
  field; `POST /rhn/manager/api/login` with JSON `{"login": ..., "password":
  ...}` and headers `Content-Type: application/json` + `X-CSRF-Token: <token>`
  returns `{"success": true}` and sets the session cookie (use a cookie jar on
  both requests); afterwards `GET /rhn/<page>.do` URLs work with that cookie.
  Source of truth: `web/html/src/manager/login/use-login-api.ts`. For *any*
  Web-UI flow, grep `web/html/src/` first — do not reverse-engineer the minified
  bundles (`/javascript/manager/main.js`).
- **Server DB map** (saves `information_schema` round trips): organizations live
  in `web_customer` (there is no `org`/`orgs` table). Frequently needed tables:
  `rhnchannel`, `rhnchannelcloned (id, original_id)`, `rhnerrata`,
  `rhnerratabuglist (errata_id, bug_id, summary, href)`,
  `rhncve (name VARCHAR(20))`, `rhntaskqueue (task_name, task_data)`, and
  `rhnserverneededcache (server_id, errata_id, package_id, channel_id)` — the
  errata cache actually populates `rhnserverneededcache`;
  `rhnserverneedederratacache` stays empty in this setup. Unique indexes live in
  `schema/spacewalk/postgres/tables/<table>_index.sql` (the
  `schema/spacewalk/common/tables` file only points there); they are partial — vendor rows (`org_id IS NULL`) are
  covered by separate index variants, so a custom-errata advisory name may
  legally coexist with a vendor one.
- **Seeding content via SQL** (when the API cannot produce a precondition):
  package rows need explicit sequence values — `rhn_pkg_name_seq`
  (rhnPackageName), `rhn_pkg_evr_seq` (rhnPackageEVR), `rhnchecksum_seq`
  (rhnChecksum, needs `checksum_type_id` from `rhnChecksumType`),
  `rhn_package_id_seq` (rhnPackage). Gotchas: `rhnPackageEVR.type` is a
  generated column (never insert it), `epoch` must be `NULL` (a CHECK rejects
  `''`), and `rhnPackageName.name` is unique — reuse an existing name row (the
  minion profile sync already creates common names). A package with
  `org_id = <org>` is visible to every user of that org
  (`package_available_to_user` is org-scoped, not group-scoped). A vendor
  erratum is a `rhnErrata` row with `org_id NULL` (NOT NULL: advisory,
  advisory_type, advisory_name, advisory_rel, product, synopsis, solution; dates
  default) plus a `rhnerratabuglist` row for its bugs.
- **Cache/queue observables** (DoS-style findings): the errata-cache cascade is
  errata add/removePackages → `UpdateErrataCacheEvent(TYPE_CHANNEL)` →
  `rhn_channel.update_needed_cache` → one `update_server_errata_cache` task per
  subscribed server in `rhntaskqueue` → delete + rebuild of
  `rhnserverneededcache`. A needed-errata row only appears if the erratum's
  package name matches a package installed on the minion with a *newer* EVR —
  seed such a package and add it to both channel and erratum to make the
  cascade measurable. The async errata-clone queue is an unbounded
  `LinkedBlockingQueue` with a single dispatcher thread by default
  (`java.message_queue.threads`); probe its drain state by calling synchronous
  `errata.clone` — it faults `-211 ... pending asynchronous errata clone jobs`
  until the queue is empty.
- Create a dedicated fixture (erratum/channel/user) per finding with traceable
  names (`SECVAL-<topic>-<n>`); side effects of one test can become evidence for
  another, but re-verify fixture state before each assertion.
- Config-channel authorization model: channel access = org match + (the
  `config_admin` access group, or visibility of >=1 *traditional* system
  subscribed via `rhnServerConfigChannel`). Salt-minion channel subscriptions
  live only in state revisions (`susestaterevisionconfigchannel` et al.) and
  never satisfy `rhn_config_channel.get_user_chan_access`. To test
  regular-user channel access on a Salt-only fleet, insert
  `(server_id, config_channel_id, position)` rows directly into
  `rhnServerConfigChannel` (no id column/sequence).
- `systemgroup.subscribeConfigChannel` (group -> channel) creates state
  revisions only; it does not populate `rhnServerConfigChannel`.
- Action lifecycle: `rhnserveraction.status` 0=Queued, 1=Picked Up,
  2=Completed, 3=Failed. Minions pick up highstate/deploy actions within
  ~1-2 min — poll the DB, not the logs. `configfiles.deploy` actions do
  execute on Salt minions (via the `configuration.deploy_files` sls).
- Jinja payloads stored in config files: use `salt['cmd.shell']` for shell
  features (redirects); `cmd.run` mangles them.
- Remote psql/salt over ssh: capture stderr, and inspect the table
  (`\d <table>` or `information_schema.columns`) before writing queries —
  column names surprise (`rhnaction.scheduler`, `rhnserveraction.result_msg`).
- Keep ssh/psql helpers in script files (`tasklist/validation/tests_*.py`),
  never in inline `python -c "..."` strings — nested shell quoting breaks
  silently. If stdout shows only the login motd ("Have a lot of fun..."), the
  remote command string was mangled, not executed.
- Cheap build<->source corroboration: stack-trace line numbers from
  `/var/log/rhn/rhn_web_ui.log` vs. worktree sources.
- XML-RPC specifics (endpoint, headers, parameter pitfalls, debugging) are
  documented in `AGENTS_XMLRPC.md`.

### VM Capabilities & Rules

1. **Podman Pre-installed:**
   - Podman is installed and active on the VM (`/usr/bin/podman`).
   - Container workloads, container builds, and test containers can be executed directly under `root@192.168.200.201`.

2. **Package Management (`zypper`):**
   - The VM runs openSUSE Tumbleweed with `zypper`.
   - The agent has full root privileges and is free to install any required packages, test suites, and build tools:
     ```bash
     ssh -i ~/workbase/id_ssh_agents -o StrictHostKeyChecking=no root@192.168.200.201 "zypper in -y <package>"
     ```

3. **Network Scope:**
   - Network access on the VM is strictly restricted to **openSUSE repositories** and **Uyuni package repositories** (proxied).
   - General internet access (arbitrary external HTTP/HTTPS domains) is blocked. All package downloads must come from configured openSUSE/Uyuni mirrors.

---

## 4. XML-RPC API Work

When writing scripts that call the server's XML-RPC API (testing, security
validation, automation), read [`AGENTS_XMLRPC.md`](AGENTS_XMLRPC.md) first. It
covers endpoint and auth essentials (including the mandatory
`Content-Type: text/xml` header), parameter-type pitfalls, current method
signatures that differ from older documentation, the reusable Python harness
under `tasklist/validation/`, and server-side debugging locations.

---

## 5. Autoinstallation Overhaul Plan (`autoinst_plan/`)

Multi-session work on autoinstallation (kernel options, kickstart
modernization, React UI, saltboot, generic install mechanisms, cobbler-exit
prep) is coordinated by a plan of record:

- `autoinst_plan/00-goals.md` — cross-session goals contract. **Read this
  first** in any session touching autoinstallation.
- `autoinst_plan/10-roadmap.md` — phase/step breakdown with statuses,
  dependencies, and the open-questions register.
- `autoinst_plan/20-kernel-options.md` — behavioural reference for the kernel
  options phase (builder contract, legacy strings, cobbler interaction).
- `autoinst_plan/00-goals.html` / `10-roadmap.html` — presentation variants;
  regenerate with `.venv/bin/python autoinst_plan/build_html.py` after editing
  the markdown sources.
- `agents-workbench/autoinstallation/` — supporting workspace: session log
  (`session-log.md`) and research findings (`findings/`) for future agents.
