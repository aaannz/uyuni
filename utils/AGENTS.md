# `utils/` — Standalone server administration / channel tools

Loaded when working in this subtree; root `AGENTS.md` still applies.

## Scope and ownership

The `spacewalk-utils` package: "Utilities that may be run against a Uyuni server"
(`utils/spacewalk-utils.spec`).

## Directory layout

- Library modules `utils/__init__.py`, `depsolver.py`, `cloneByDate.py`; CLI scripts
  `spacewalk-common-channels`, `spacewalk-clone-by-date`,
  `spacewalk-manage-channel-lifecycle`, `taskotop`, `spacewalk-watch-channel-sync.sh`;
  channel-definition INIs installed as config; tests in `utils/tests/`.

## Entry points

- `utils/taskotop` — "Displays a summary of Taskomatic activities in progress".
- `utils/spacewalk-clone-by-date` — CLI wrapper for `utils/cloneByDate.py` channel cloning.

## Build and test commands

From `utils/`:

    make -f Makefile.python __pytest

Tests import extensionless scripts via a symlink helper (`utils/tests/helpers.py`); the
suite currently covers `spacewalk-manage-channel-lifecycle`. Docker variants need docker
and the internal registry `registry.mgr.suse.de`.

## Gotchas

- Heavy runtime deps on installed backend packages (`python3-solv`,
  `python3-uyuni-common-libs`, `spacewalk-backend-*` per `utils/spacewalk-utils.spec`) —
  these tools assume a Uyuni server environment, not just a source checkout.
- CI: `.github/workflows/python-checkstyle.yml` excludes `utils/tests/` from its
  changed-file test mapping.
