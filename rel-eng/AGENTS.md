# `rel-eng/` — release engineering: tito, OBS tooling, version bumps

Loaded when working in this subtree; root `AGENTS.md` still applies.

## Scope and ownership

Tito package metadata, OBS release tooling, and version bump scripts. Release/OBS
operations affect external infrastructure and must NOT be run without explicit
authorization.

## Directory layout

- `rel-eng/packages/<package>` — one marker file per package mapping version to
  relative path (e.g. `rel-eng/packages/server-image`).
- `rel-eng/tito.props` — global tito config (`tito.tagger.SUSETagger`,
  `no_default_changelog = 1`, `lib_dir = rel-eng/custom/`).
- `rel-eng/releasers.conf` — tito releaser definitions (OBSReleaser per OBS project).
- `rel-eng/custom/` — tito builder/tagger library code.

## Entry points

- `rel-eng/release-uyuni` — releases a new Uyuni version to OBS (copies packages from
  `systemsmanagement:Uyuni:Master` into version/Snapshots projects); requires OBS
  credentials and `-u/--uyuni-version`.
- `rel-eng/push-version` — rewrites the `Version:` field of every `.spec` found via
  `rel-eng/packages/` and prepends a changelog entry; usage
  `--version=<X.Y> --bugzilla=<bsc#...>`.
- `rel-eng/uyuni-check-version`, `rel-eng/fixed-in-version` — version query helpers.
- `rel-eng/build-packages-local-obs.sh` — local `osc build` wrapper for spacewalk packages.

## Gotchas

- Package changelogs live next to each package's `.spec`, and CI forbids editing the
  master `.changes` directly — use fragments named `<pkg>.changes.<author>.<topic>`
  (`.github/workflows/changelogs/changelogs.py`). The fragments are merged into the
  master changelog at release time by `mkchlog`, a tool living in the separate
  release-engineering repository (not this repo). `push-version` is the sanctioned
  release-time exception that does edit master changelogs.
- Do not execute `rel-eng/release-uyuni`, `rel-eng/push-version`, or related tools —
  there is no reliable dry-run mode, and they affect external OBS infrastructure.
- Maven pom versions (`java/`, `branding/`, `microservices/`) have NO bump automation:
  `rel-eng/push-version` only rewrites `.spec` `Version:` fields; poms must be edited
  manually in lockstep with their specs. tito (`rel-eng/packages/` markers) affects
  tags/changelogs, not poms.

## Pointers

- `rel-eng/Makefile.python` — pip/pylint env setup helpers consumed by packaged Python
  components (also pins pylint 2.3.1).
- CI inventory: `.github/workflows/AGENTS.md`.
