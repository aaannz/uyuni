# `python/` — Server-side Python (backend, common libs, rhnlib, tests)

Loaded when working in this subtree; root `AGENTS.md` and `AGENTS_CUSTOM.md` still apply.

## Scope and ownership

`python/spacewalk/` is the server backend code, `python/uyuni/` the uyuni-commons
libraries, `python/rhn/` the rhnlib code, and `python/test/` all tests (per
`python/README.md`). Two standalone Python sub-projects also live here:
`billingdataservice/` and `uyuni-cobbler-helper/`.

## Directory layout

- `python/spacewalk/` — backend packages `common`, `server`, `upload_server`,
  `satellite_tools`, `po`, `wsgi`; packaged as `spacewalk-backend`.
- `python/uyuni/common/` — shared libs (checksum, cli, fileutils, rhnLib, rhn_rpm,
  rhn_deb, rhn_mpm, usix); packaged as `uyuni-common-libs`.
- `python/rhn/` — rhnlib transport/XML-RPC modules (rpclib, transports, SSL).
- `python/test/unit/` (split per `spacewalk` and `uyuni`) and `python/test/integration/`.
- `python/linting/` — `lint.sh`, `pylintrc`, and the lint container `Dockerfile`.

## Entry points

- Backend CLI tools installed from this tree include `spacewalk-cfg-get` and, from
  `satellite_tools`, `spacewalk-repo-sync`, `spacewalk-remove-channel`, `spacewalk-debug`,
  `spacewalk-data-fsck`, `mgr-sign-metadata`, `update-packages`
  (`python/spacewalk/spacewalk-backend.spec`).
- `python/billingdataservice/billingdataservice.py` — Flask app using
  `spacewalk.server.rhnSQL`; packaged as `billing-data-service`.
- `python/uyuni-cobbler-helper/` — DB-backed helpers for Cobbler snippets.

## Build and test commands

From `python/`:

    python3 -m pytest test/unit/

- New tests must use pytest and stay runnable without a container (`python/README.md`).
- uyuni-common-libs only: `make -f Makefile.common-libs test` (from `python/uyuni/`).
- Integration tests need PostgreSQL:
  `sh ./backend-unittest-pgsql.sh` from `susemanager-utils/testing/automation`.
- Lint/format via container: `./python/linting/lint.sh <path>` or `lint.sh -a` for
  modified files; the script runs black then pylint (`python/linting/lint.sh`).
- Docker targets (need docker + uyuni-master-pgsql image):
  `make -f Makefile.backend docker_no_db_tests` / `docker_pgsql_tests` (from `python/spacewalk/`).

## Gotchas

- `python/test/conftest.py` creates a temp `rhn.conf` and sets `RHN_CONFIG_PATH` /
  `RHN_CONFIG_DEFAULTS_PATH` because rhnConfig needs config files at import time.
- `python/spacewalk/Makefile.python` and `Makefile.backend` reference
  `--rcfile=spacewalk/pylintrc`, but no pylintrc exists at that path; CI instead runs
  `pylint --rcfile=/root/.pylintrc` in the container built from `python/linting/Dockerfile`.
- `python/spacewalk/Makefile.defs` defaults `PYTHON_BIN` to `python`, not `python3`.
- CI (`.github/workflows/python-checkstyle.yml`) runs black+pylint on changed `.py` files
  in the lint container and pytest on Python 3.11/3.13. Its changed-file test mapping
  excludes `python/test/unit/`, `python/billingdataservice/tests/`, and
  `python/spacewalk/server/test` (+ `python/spacewalk/server/importlib/test`) — those
  belong to the container harnesses (`susemanager-utils/testing/automation/
  backend-unittest-pgsql.sh` drives `python/test/docker-backend-*-tests.sh`), which run
  manually or via QA infra, NOT from any GitHub workflow. The legacy collocated tests
  under `python/spacewalk/server/` are documented broken (`python/README.md`) and have
  no runner at all.
- rhnlib (`python/rhn/`) has no tests anywhere: no test dir, no `%check` in
  `python/rhn/rhnlib.spec`, no CI reference; only lint runs on changed files.

## Pointers

- Backend runtime deps: `python/spacewalk/spacewalk-backend.spec`.
- Billing service test setup: `python/billingdataservice/pytest_setup.sh`.
- Related guides: `susemanager/AGENTS.md`, `spacecmd/AGENTS.md`, `utils/AGENTS.md`.
