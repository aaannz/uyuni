# `reporting/` — CSV report CLI against the report database

Loaded when working in this subtree; root `AGENTS.md` still applies.

## Scope and ownership

The `spacewalk-reports` package: "Script based reporting" that retrieves server data in
CSV format (`reporting/spacewalk-reports.spec`).

## Directory layout

- `reporting/spacewalk-report` — CLI script; `reports.py` — report-definition loader;
  `reporting/reports/data/` and `reporting/reports/legacy/` — report SQL definitions;
  `pylintrc`, `Makefile.python`, `spacewalk-reports.spec`.

## Entry points

- `reporting/spacewalk-report` uses `spacewalk.common.rhnConfig` and
  `spacewalk.server.rhnSQL`; connects with `rhnSQL.initDB(reportdb=options.use_reportdb)`.

## Build and test commands

    make -f Makefile.python __pylint      # pylint --rcfile=pylintrc over *.py

Docker variants (`docker_pylint`, `docker_shell`) require docker and the internal
registry `registry.mgr.suse.de` image `uyuni-master-pgsql`.

## Gotchas

- Report definitions are looked up at the installed path `/usr/share/spacewalk/reports`,
  so the CLI reads installed copies, not the repo tree directly (`reporting/reports.py`).
- The pgsql Docker targets point at an internal SUSE registry, not the public opensuse
  registry used elsewhere.

## Pointers

- The reporting DB schema lives in `schema/reportdb/` — see `schema/AGENTS.md`.
