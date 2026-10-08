# `spacewalk/` — Server packaging dirs: admin tools, certs, setup, config

Loaded when working in this subtree; root `AGENTS.md` still applies.

## Scope and ownership

Package subdirectories, mostly Perl/Python scripts and systemd units (NOT the backend
code — that lives in `python/spacewalk/`):

- `spacewalk/admin/` — `spacewalk-admin`, utility scripts (`spacewalk-service`,
  `mgr-monitoring-ctl`, `rhn-config-satellite.pl`, `salt-secrets-config.py`, ...).
- `spacewalk/certs-tools/` — `spacewalk-certs-tools` (`rhn-ssl-tool`,
  `mgr-ssl-cert-setup`, `rhn-bootstrap`).
- `spacewalk/setup/` — `spacewalk-setup` initial setup tools; installer logic is the
  Perl module `Spacewalk::Setup` (`perldoc`, see `spacewalk/setup/README`).
- `spacewalk/config/` — `spacewalk-config`.
- `spacewalk/package/` — meta package `spacewalk`.
- `spacewalk/uyuni-setup-reportdb/` — PostgreSQL reporting-DB setup scripts
  (installation-time tooling; long-term direction is to fold this into the server
  container entrypoint).

## Build and test commands

    ./spacewalk/setup/share/run_pytest.sh   # venv + pytest over spacewalk/setup/share/tests/

No dedicated Makefile test targets exist for the admin/certs-tools/config dirs.

## Gotchas

- `spacewalk/setup/` is primarily Perl (CPAN-style `Makefile.PL`), not Python; only its
  shared tests are pytest-based.
- `spacewalk/admin/spacewalk-admin.spec` shows heavy cross-package Requires (cobbler,
  postfix, spacewalk-backend-tools, uyuni-setup-reportdb) — test in a server-like
  environment.

## Pointers

- The one pytest suite here: `spacewalk/setup/share/tests/test_embedded_diskspace_check.py`.
- Backend code: `python/AGENTS.md`.
