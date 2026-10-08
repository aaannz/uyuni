# `susemanager/` — Server-side Python CLI tools

Loaded when working in this subtree; root `AGENTS.md` still applies.

## Scope and ownership

Server-side Python CLI tools for Uyuni/SUSE Manager administration (`mgr-sync`,
`mgr-create-bootstrap-repo`, `mgr-delete-patch`, `mgr-clean-old-patchnames`,
`mgr-salt-ssh`) plus supporting modules (`authenticator.py`, `helpers.py`,
`errata_helper.py`, `package_helper.py`, `mgr_sync/`). Subpackages of
`susemanager/susemanager.spec`: `susemanager`, `tools`, `tools-salt`, `bash-completion`.

## Directory layout

- `susemanager/src/` — Python modules and CLI scripts; `susemanager/src/mgr_sync/`
  implements mgr-sync.
- `susemanager/src/test/` — pytest unit tests (plus `fixtures/`, `test_mgr_sync/`).
- `susemanager/rhn-conf/` — rhn config snippets; wires the bootstrap datamodule.
- `susemanager/bash-completion/` — completion scripts (per-tool `.bash` files +
  `susemanager/bash-completion/completions/_common.bash`).
- `susemanager/po/` — translations; `susemanager/bin/` — shell helpers.

## Entry points

- Scripts installed to `/usr/sbin`: `mgr-clean-old-patchnames`,
  `mgr-create-bootstrap-repo`, `mgr-delete-patch`, `mgr-sync`; `mgr-salt-ssh` to
  `/usr/bin` (`susemanager/src/Makefile`).
- `susemanager/src/mgr_bootstrap_data.py` — GENERATED (`# DO NOT EDIT !!!` header),
  installed to `/usr/share/susemanager/` and consumed via
  `bootstrap_repo_datamodule = mgr_bootstrap_data` in
  `susemanager/rhn-conf/rhn_server_susemanager.conf`; `mgr-create-bootstrap-repo` reads
  its `DATA` dict. Its content mirrors the client bootstrap definitions in
  `spacewalk/certs-tools/rhn_bootstrap.py` and
  `spacewalk/certs-tools/rhn_bootstrap_strings.py` — keep them in sync; do not
  hand-edit this file.

## Build and test commands

From `susemanager/` (targets in `susemanager/Makefile.susemanager`):

    make -f Makefile.susemanager docker_tests     # supported path: containerized pytest
    make -f Makefile.susemanager docker_pylint    # containerized pylint

- The tests are NOT to be run outside the container; the local `unittest` target
  exists but requires `PYTHONPATH` into `../python/` and `../client/` and mutates
  the worktree (see Gotchas).
- CI checks changed `.py` files with black + pylint
  (`.github/workflows/python-checkstyle.yml`).

## Gotchas

- The `unittest` target MUTATES the worktree: it creates a symlink named `susemanager`
  inside `python/spacewalk/` pointing at the susemanager sources and removes it
  afterwards (`susemanager/Makefile.susemanager`). Use the `docker_tests` target
  instead — tests are not meant to run outside the container.
- Tests must be fully automated and isolated: no output, no database access, no
  filesystem reads/writes (`susemanager/src/test/README`).
- Docker targets reference the internal registry `registry.mgr.suse.de` and need
  `rhnlib` / `spacewalk-client-tools` sources from `../client/`.

## Pointers

- Bash completions: `susemanager/bash-completion/README.md`.
- Shared Makefile infrastructure: `susemanager/Makefile.defs`, `rel-eng/Makefile.python`.
