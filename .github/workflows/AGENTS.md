# `.github/workflows/` — CI definitions

Loaded when working in this subtree; root `AGENTS.md` still applies.

## PR coordination and acceptance tests

- `acceptance_tests_coordinator.yml` ("TestFlow") — runs on every PR, computes
  recommended Cucumber tests with `node .github/scripts/test_covering_pr.js`, then
  delegates to the reusable base workflow for recommended, acceptance, and additional
  (sharded) runs.
- `acceptance_tests_base.yml` ("Tests") — reusable `workflow_call` that builds/exercises
  a containerized server and runs a given runner script.
- `acceptance_tests_scheduler.yml` — scheduled acceptance tests, cron every 12 hours on
  the upstream master.
- `acceptance_tests_feedback_trigger.yml` / `acceptance_tests_feedback_comment.yml` —
  post test-result comments on PRs.

## Frontend (web/)

All use Node.js 22.x: `frontend-build.yml`, `frontend-lint.yml`,
`frontend-lint-fix-pr.yml` (opens an automatic lint-fix PR),
`frontend-typescript-compilation.yml`, `frontend-unit-tests.yml`,
`frontend-dependency-audit.yml`, `storybook-pages.yml`.

## Other CI

- Package hygiene: `changelogs.yml` — fails the PR if a master `.changes` file was
  modified directly; logic in `.github/workflows/changelogs/changelogs.py`, enforcing the
  `<pkg>.changes.<author>.<topic>` fragment filename format.
- CI images: `build_containers.yml`, `build_controller_test_container.yml`,
  `build_redis_container.yml`.
- Language checks: `java-checkstyle.yml` (Ant-based), `java-microservices-mvn-build.yml`
  (JDK 17), `python-checkstyle.yml` (lint container + pytest matrix 3.11/3.13),
  `rubocop.yml`, `search-server-compile.yml`, `yard-doc-test-framework.yml`,
  `susemanager-sls-unit-tests.yml`, `schema-migration-reportdb-test-pgsql.yml`,
  `helm-charts.yml`, `check_entrypoint_scripts.yml`, `check_translations.yml`,
  `update_translations.yml`, `git-checks.yml`, `hash-check.yml`.
- PR meta: `labeler.yml`, `pr-event-trigger.yml`, `verify-done-checkboxes.yml`,
  `stale.yml`, `sonarcloud.yml`, `prebuilt_devcontainer.yml`.
