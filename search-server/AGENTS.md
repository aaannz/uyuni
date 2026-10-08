# `search-server/` — standalone full-text search daemon (rhn-search)

Loaded when working in this subtree; root `AGENTS.md` still applies.

## Scope and ownership

`spacewalk-search`, "Spacewalk Full Text Search Server": indexes packages, systems,
errata and documentation from the DB. Still a user-facing feature, although the
codebase is old. Ant/Ivy project, no Maven (`build.xml` +
`ivy.xml`); search index uses Lucene 2.4.

## Entry points

- `main()` in `search-server/spacewalk-search/src/java/com/redhat/satellite/search/Main.java`;
  index cleanup tool in `DeleteIndexes.java`.
- Installed as systemd service `rhn-search.service`.
- The Java server calls it via XML-RPC at `http://<host>:2828/RPC2` by default
  (`java/core/src/main/java/com/redhat/rhn/common/conf/ConfigDefaults.java`), e.g. SCAP
  search; pulled into server installs by `spacewalk/package/spacewalk.spec`.

## Build and test commands

CI compiles with Ant (`.github/workflows/search-server-compile.yml`):

    ant -f search-server/spacewalk-search/build.xml resolve
    ant -f search-server/spacewalk-search/build.xml compile

An Ant `test` target exists in `search-server/spacewalk-search/build.xml`.

## Gotchas

- Build system differs from `java/` (Ant/Ivy, no pom) — Maven commands do not apply here.
- CI only triggers on changes under `search-server/spacewalk-search/` Java/XML files.

## Pointers

- `java/AGENTS.md` for the server side that consumes this daemon.
