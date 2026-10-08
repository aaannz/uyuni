# `spacecmd/` — Python CLI client for the XML-RPC API

Loaded when working in this subtree; root `AGENTS.md` still applies.

## Scope and ownership

Standalone Python CLI client for the Uyuni/SUSE Manager XML-RPC API: "Command-line
interface to %{productprettyname} servers" (`spacecmd/spacecmd.spec`).

## Directory layout

- `spacecmd/src/bin/spacecmd` — executable entry script.
- `spacecmd/src/spacecmd/` — one module per command domain (`system.py`,
  `softwarechannel.py`, `kickstart.py`, `user.py`, `ssm.py`, ...), plus `shell.py` and
  shared `utils.py`.
- `spacecmd/tests/` — pytest tests per domain, shared fakes in
  `spacecmd/tests/helpers.py`.

## Build and test commands

From `spacecmd/`:

    make -f Makefile.python __pytest         # installs package via setup.py, then pytest tests/
    make -f Makefile.python docker_pytest    # same inside uyuni-master-pgsql container
    make -f Makefile.python __pylint

## Gotchas

- Targets are `__pytest`/`__pylint` (double underscore), not `unittest`.
- Tests are offline: the `shell` fixture builds a `MagicMock` client with a fake session
  (`spacecmd/tests/helpers.py`); no server or database needed.
- `spacecmd/tests/` is excluded from the generic changed-file pytest CI job; the
  containerized harness is `susemanager-utils/testing/automation/spacecmd-unittest.sh`.
- `setup.py` derives the package version from the `*.changes` changelog.
- pylint is pinned to 2.3.1 via `rel-eng/Makefile.python` (pip/network).

## Pointers

- XML-RPC specifics (endpoint, auth, signatures): `AGENTS_XMLRPC.md`.
