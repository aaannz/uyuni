# Repository Instructions

## Scope and source of truth

These instructions apply to the entire repository. If a subdirectory later
contains a more specific `AGENTS.md`, follow both files, with the nearer file
taking precedence for that subtree.

If `AGENTS_CUSTOM.md` exists in the repository root, follow its local
environment instructions as overrides to this file.

This is a large, long-lived Uyuni/Spacewalk monorepo. Treat code, manifests,
build files, and CI workflows in the target branch as authoritative. Do not
assume that conventions are uniform across subsystems: each subsystem has its
own `AGENTS.md` (map below) with build commands, entry points, and gotchas.
Cross-cutting topics are indexed in `docs/index.md` — read topics on demand
instead of exploring broadly.

## Repository map

| Subtree | What it is | Guide |
|---|---|---|
| `java/` | Uyuni server: web UI, business layer, XML-RPC API, Taskomatic | `java/AGENTS.md` |
| `microservices/` | Shared Maven parent, `uyuni-java-common`, CoCo attestation | `microservices/AGENTS.md` |
| `branding/` | Branding jar required by `java/` builds | `branding/AGENTS.md` |
| `search-server/` | Standalone Lucene full-text search daemon (Ant/Ivy) | `search-server/AGENTS.md` |
| `web/` | React/TypeScript frontend (npm, Jest, Storybook) | `web/AGENTS.md` |
| `python/` | Server backend, common libs, rhnlib, unit tests | `python/AGENTS.md` |
| `schema/` | PostgreSQL schemas (main + reportdb), migrations | `schema/AGENTS.md` |
| `susemanager/` | Server-side CLI tools (mgr-sync, mgr-create-bootstrap-repo, ...) | `susemanager/AGENTS.md` |
| `susemanager-utils/` | Salt states/modules (susemanager-sls), mgr-libmod, test harnesses | `susemanager-utils/AGENTS.md` |
| `spacecmd/` | Python CLI client for the XML-RPC API | `spacecmd/AGENTS.md` |
| `uyuni/` | Base package, PAYG timer, storage setup | `uyuni/AGENTS.md` |
| `reporting/` | CSV report CLI against the report DB | `reporting/AGENTS.md` |
| `utils/` | Channel/admin tools (clone-by-date, common-channels, taskotop) | `utils/AGENTS.md` |
| `spacewalk/` | Packaging dirs: admin tools, certs, setup, config | `spacewalk/AGENTS.md` |
| `containers/` | Container image definitions + Helm charts | `containers/AGENTS.md` |
| `rel-eng/` | tito metadata, OBS release tooling, version bumps | `rel-eng/AGENTS.md` |
| `testsuite/` | Cucumber/Playwright acceptance suite | `testsuite/AGENTS.md` |
| `.github/workflows/` | CI definitions | `.github/workflows/AGENTS.md` |
| `client/`, `projects/`, `proxy/`, `scripts/`, `documentation/` | small/peripheral dirs | `docs/peripheral-subsystems.md` |
| `tasklist/`, `agents-workbench/` | local agent artifacts, not upstream content | `AGENTS_XMLRPC.md`, `agents-workbench/README.md` |

## Working practices

- Make the smallest complete change that solves the requested problem. Keep
  changes scoped to the requested subsystem; do not add speculative
  abstractions, unnecessary layers, duplicate helpers, or other code bloat.
- Do not opportunistically modernize adjacent legacy code, perform unrelated
  refactors, or mix broad formatting with a functional change.
- Optimize changes for readability. Prefer straightforward control flow,
  descriptive names, cohesive functions, and existing repository patterns over
  clever or overly generic solutions. Add comments only when intent cannot be
  made clear through the code itself.
- Keep diffs easy to review. Avoid unnecessary file churn, preserve local
  style, and separate logically distinct changes when practical.
- The repository maintains multiple active main branches for supported product
  lines as well as Uyuni development. Confirm the intended base and target
  branch before making version-sensitive changes, backports, or pull requests;
  do not assume the default branch is the correct destination.
- When validating behavior against a live deployment (VM or installed server),
  verify that the deployed package version matches the working branch before
  dynamic testing, and record the exact versions in the report.
- Preserve compatibility and existing public behavior unless the task
  explicitly changes it. Uyuni supports mixed client platforms and long-lived
  upgrade paths.
- Search for an existing implementation, helper, test pattern, or schema
  migration before adding a new one. Reuse domain helpers and existing test
  patterns; subsystem guides list where they live.
- Keep dependency changes intentional. Update the corresponding lockfile or
  package metadata and explain why the dependency is needed.
- Never commit credentials, tokens, hostnames from private environments, or
  generated test secrets.
- Do not run release, OBS promotion, image publication, or deployment commands
  unless the user explicitly requests that operation. These commands affect
  external infrastructure and may consume credentials from the environment.
- Do not discard unrelated worktree changes. Avoid destructive Git operations.
- Update nearby documentation when commands, configuration, public behavior,
  or operational procedures change.

## Generated and derived files

Edit sources, not build output:

- Do not edit or commit `web/html/src/dist/`; `npm run build` regenerates it.
- Do not edit `schema/spacewalk/postgres/main.sql` (or the reportdb equivalent)
  directly; they are generated by `blend` from the individual sources.
- Treat `susemanager/src/mgr_bootstrap_data.py` as generated; it is marked
  `DO NOT EDIT`.
- `java/repository/` and `java/.obs-to-maven-cache/` are generated Maven repos.
- Let npm manage package lockfiles. Do not hand-edit them.
- Production frontend builds regenerate `web/spacewalk-web.spec` and
  `web/html/src/vendors/npm.licenses.*`; commit the refreshed result.

## Checks and commands

Per-subsystem build, test, and lint commands live in each subtree's `AGENTS.md`
(map above) — always prefer those over guessing. Global rules:

- Run the smallest relevant checks first. Expand to broader tests only when the
  change crosses subsystem boundaries or targeted checks expose wider failures.
- Do not run acceptance tests (`testsuite/`) locally; they require a large,
  specialized environment. Rely on the CI workflows for their execution.
- Package changelogs: never edit a master `<pkg>.changes` file; add a fragment
  beside it named `<pkg>.changes.<author>.<topic>` (CI enforces this).
- `rel-eng/release-uyuni`, `rel-eng/push-version`, and related tools are
  release-engineering operations, not routine validation commands.

## Agent-facing documentation layout

- Root (this file): global rules + map. Kept small by design.
- `<subtree>/AGENTS.md`: per-subsystem guides (build/test commands, entry
  points, gotchas) — loaded when working in that subtree.
- `docs/index.md`: topic registry for cross-cutting documentation.
- `AGENTS_CUSTOM.md` / `AGENTS_XMLRPC.md`: environment and XML-RPC specifics.
- `agents-workbench/`: tooling that maintains the agent docs, including
  `agents-workbench/scripts/lint-docs.py`, which verifies every path cited in
  these guides — run it after editing any of them.
