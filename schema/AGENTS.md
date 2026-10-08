# `schema/` — database schema subsystem

Loaded when working in this subtree; root `AGENTS.md` still applies.

## Scope and ownership

Two PostgreSQL schemas: main server schema in `schema/spacewalk/` and reporting DB in
`schema/reportdb/`, plus `schema/sm-specific-schema-patches/`. Change the individual
schema source files and dependency declarations — never generated output.

## Directory layout

- DB-agnostic sources in `schema/spacewalk/common/{tables,views,data,schemas}`;
  PG-specific in `schema/spacewalk/postgres/{class,tables,procs,packages,views,triggers,
  data,synonyms,quartz}` (map in `schema/spacewalk/README`).
- Procedures: `postgres/procs/*.sql`; packages are spec+body pairs
  `postgres/packages/<name>.pks` + `<name>.pkb`.
- Statement ordering comes from per-dir `<dir>.deps` files (e.g.
  `schema/spacewalk/postgres/tables/tables.deps`), processed by `blend` (format
  documented in `schema/spacewalk/README`).
- Upgrades: `schema/spacewalk/upgrade/<old>-to-<new>/*.sql`; new work in
  `schema/spacewalk/upgrade/next/` named like `100-my-change.sql`
  (`schema/spacewalk/upgrade/next/README.md`).

## Build and test commands

    make -C schema/spacewalk/postgres main       # blend -> main.sql (generated, git-ignored)
    make -C schema/reportdb/postgres main        # reportdb equivalent
    perl schema/spacewalk/schema-source-sanity-check.pl   # part of `all` in Makefile.schema

- CI runs reportdb migration tests via
  `susemanager-utils/testing/automation/schema-migration-reportdb-test-pgsql.sh`
  (`.github/workflows/schema-migration-reportdb-test-pgsql.yml`).
- RPM build: `make_build -f Makefile.schema SCHEMA=... VERSION=... RELEASE=...`
  (`schema/spacewalk/susemanager-schema.spec`).

## Gotchas

- Never edit `main.sql` or the `postgres/*/common/` copies — all regenerated (`blend` +
  sub-Makefile `cp`).
- `schema/spacewalk/upgrade/next/` scripts are re-applied on every server start, so they
  must be
  idempotent, and they are applied regardless of whether the schema version was bumped;
  at release `tito` moves them into a versioned dir — do not create versioned dirs by
  hand (`schema/spacewalk/upgrade/next/README.md`). reportdb is identical but applied
  via `spacewalk-schema-upgrade --reportdb`.
- reportdb's Makefile calls `blend` from PATH (installed by the susemanager-schema RPM),
  not `../blend`.
- `schema/HOWTO` documents Oracle-era setup; Oracle has been unsupported for years —
  do not follow it.
- There is no CI migration test for the main schema (only reportdb has one, see
  `.github/workflows/schema-migration-reportdb-test-pgsql.yml`); validate main-schema
  changes with the sanity check and a local `make -C schema/spacewalk/postgres main`.
- The Perl schema-upgrade tool `schema/spacewalk/spacewalk-schema-upgrade` is
  functional (currently being rewritten in Python).

## Pointers

- `.deps` syntax and special files `clean.sql`/`start.sql`/`end.sql`:
  `schema/spacewalk/README`.
- Upgrade tool used on installed servers: `schema/spacewalk/spacewalk-schema-upgrade`.
- reportdb doc generation: `docs` target in `schema/reportdb/Makefile.schema`.
- `schema/reportdb/check_reportdb_doc` — NOT dead code: it verifies the generated
  `schema/reportdb/postgres/main.sql` against `schema/reportdb/postgres/documentation.sql`
  (both generated) and runs in CI via
  `susemanager-utils/testing/docker/scripts/generate-reportdb-docs.sh`, invoked by
  `susemanager-utils/testing/automation/schema-migration-reportdb-test-pgsql.sh`.
