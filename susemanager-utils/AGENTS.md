# `susemanager-utils/` — Salt states/modules, mgr-libmod, test harnesses

Loaded when working in this subtree; root `AGENTS.md` still applies.

## Scope and ownership

Subsystems: `susemanager-sls/` (Salt states and Salt extension code shipped to
minions/master), `mgr-libmod/` (modular dependency resolver for content lifecycle
management), `performance/` (Locust load tests), `supportutils-plugin-susemanager/`
(supportconfig plugin), and `susemanager-utils/testing/automation/` (containerized test
harness scripts).

## Directory layout (susemanager-sls)

- `salt/` — static state tree, one directory per feature (`bootstrap/`, `channels/`,
  `distupgrade/`, `images/`, `packages/`, `proxy/`, `scap/`, ...); installed to
  `/usr/share/susemanager/salt`.
- `salt-ssh/`, `reactor/`, `scap/`, `formulas/` + `formula_metadata/`.
- `modules/` — master-side Salt extensions:
  `susemanager-utils/susemanager-sls/modules/pillar/suma_minion.py`, `tops/`, `runners/`,
  `engines/`, `roster/`.
- `src/` — Python `grains/`, `beacons/`, `modules/`, `states/` shipped as Salt
  `_grains`/`_beacons`/`_modules`/`_states`, plus unit tests in
  `susemanager-utils/susemanager-sls/src/tests/`. A
  top-level `test/` directory (`test_engine.py`, `test_pillar_suma_minion.py`) is run
  by CI (`.github/workflows/susemanager-sls-unit-tests.yml`).

## Build and test commands

From `susemanager-utils/susemanager-sls/` (Makefile.python includes
`rel-eng/Makefile.python`):

    make -f Makefile.python __pytest         # pytest in src/tests/
    make -f Makefile.python docker_pytest    # same inside uyuni-master-pgsql container (preferred, per src/README.md)

CI runs on PRs touching `susemanager-utils/susemanager-sls/`: pytest in `test/`, then
pytest in `susemanager-utils/susemanager-sls/src/tests/`
(`.github/workflows/susemanager-sls-unit-tests.yml`).

## Gotchas

- "Keep in sync with salt/salt.spec": the Python flavor switches between `python311`
  and `python3` per SLE version (`susemanager-utils/susemanager-sls/susemanager-sls.spec`).
- Tests import packages with relative double-dot imports
  (`susemanager-utils/susemanager-sls/src/README.md`).
- pylint targets pull in `update_pip_env` from `rel-eng/Makefile.python`, which removes
  the distro pylint and pip-installs pylint==2.3.1 (network required).
- mgr-libmod has no Makefile; its `tests/` are excluded from the generic changed-file CI job.
- Preserve idempotency and platform/version guards in states — see root `AGENTS.md` Salt note.

## Pointers

- Harness scripts and container refs: `susemanager-utils/testing/automation/`
  (`VERSION.Uyuni` pins container versions). These harnesses (e.g.
  `susemanager-utils/testing/automation/susemanager-unittest.sh`,
  `spacecmd-unittest.sh`) are actively maintained and executed by the QA-managed
  test infrastructure.
- Load tests: `susemanager-utils/performance/README.md`.
