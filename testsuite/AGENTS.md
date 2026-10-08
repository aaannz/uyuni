# `testsuite/` — Cucumber/Playwright acceptance suite

Loaded when working in this subtree; root `AGENTS.md` still applies.

## Scope and ownership

Automated acceptance/integration suite: Cucumber (Gherkin) features driven through
Capybara, with UI scenarios automated by Playwright via the
`capybara-playwright-driver` gem (`testsuite/README.md`).

## Directory layout

- `testsuite/features/` — feature files grouped by domain phase (`core/`, `reposync/`,
  `init_clients/`, `secondary/`, `finishing/`, `proxy/`, `kubernetes/`,
  `build_validation/`, `github_validation/`), plus `testsuite/features/step_definitions/`
  (Ruby step files) and `testsuite/features/support/` (env.rb, helpers).
- `testsuite/run_sets/` — YAML files defining feature execution order per run set;
  core features have an "IMMUTABLE ORDER" comment block and initialize the server.
- `testsuite/config/cucumber.yml` — Cucumber profiles, one `--tags @scope_*` profile
  per domain.
- `testsuite/podman_runner/` — numbered shell scripts that stand up the full
  containerized environment; `testsuite/podman_runner/run` is the entry.
- `testsuite/dockerfiles/` — images for controller, server, minions, buildhost, etc.
- `testsuite/documentation/` — step/guidelines/idempotency/Playwright docs.

## Build and test commands

Two entry paths (never both at once):

- CI: `.github/workflows/acceptance_tests_coordinator.yml` picks tests with
  `node .github/scripts/test_covering_pr.js`, then fans out to the reusable
  `.github/workflows/acceptance_tests_base.yml`, which runs the numbered scripts
  directly as steps (e.g. `20_run_recommended_tests.sh`, `18_run_secondary_tests.sh`,
  sharded `22_run_secondary_parallelizable_tests_subset.sh N`). CI never calls `run`.
- Local/manual: `testsuite/podman_runner/run` executes all numbered scripts
  sequentially (00-26; macOS forks `03_run_controller_and_registry_and_buildhost_darwin.sh`).
  Steering env vars: `UYUNI_PROJECT`, `UYUNI_VERSION`, `AUTH_REGISTRY`,
  `NO_AUTH_REGISTRY`, `AUTH_REGISTRY_CREDENTIALS`, `PUBLISH_CUCUMBER_REPORT`.
  Rake tasks (`rake cucumber:<run_set>`) execute inside the controller container.

## Gotchas — why this cannot run locally

- The documented way to run the suite is via sumaform, not from a checkout; the
  podman_runner path needs a full multi-node podman stack (server, controller, minions,
  registries, buildhost, db — see `testsuite/podman_runner/00_clean_env.sh`) plus a
  local package mirror.
- The controller needs the Node.js `playwright` CLI and a Playwright-managed Chromium
  alongside Ruby; `playwright-ruby-client` is pinned and MUST match the npm playwright
  version; there is no Gemfile.lock (`testsuite/Gemfile`).
- Run sets execute in a fixed phase order (sanity_check, core, reposync, init_clients,
  secondary, secondary_parallelizable, finishing); core features are intentionally NOT
  idempotent, secondary ones are.
- Do not run acceptance tests locally as part of routine validation — rely on CI.

## Pointers

- CI integration: `.github/workflows/acceptance_tests_coordinator.yml` and
  `.github/workflows/acceptance_tests_base.yml` — see `.github/workflows/AGENTS.md`.
