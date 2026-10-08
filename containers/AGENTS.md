# `containers/` — container image definitions and Helm charts

Loaded when working in this subtree; root `AGENTS.md` still applies.

## Scope and ownership

Container images for Uyuni server and proxy, built on OBS from this directory; the
server image is the containerized Uyuni server (systemd as PID 1, not usable
standalone) — `containers/server-image/README.md`.

## Directory layout

- Server side: `server-image/`, `server-postgresql-image/`,
  `server-database-migration-image/`, `server-saline-image/`,
  `server-hub-xmlrpc-api-image/`, `server-attestation-image/`.
- Proxy side: `proxy-httpd-image/`, `proxy-salt-broker-image/`, `proxy-squid-image/`,
  `proxy-ssh-image/`, `proxy-tftpd-image/`.
- Monitoring images: `monitoring-*-image/`.
- Helm charts: `server-helm/` and `proxy-helm/` (each with `Chart.yaml`, `templates/`,
  `test.sh`).

## Entry points

- Per image: `Dockerfile`/`Dockerfile.uyuni` pair (Uyuni variant tagged
  `uyuni/server:latest` via the `#!BuildTag` hint), an OBS `_service` file running the
  `kiwi_metainfo_helper` and `docker_label_helper` buildtime services, and a
  `tito.props` using `tito.tagger.SUSEContainerTagger`.
- Helm charts are validated by their own `test.sh`.

## Build and test commands

Image publication goes through OBS — do not run without explicit authorization
(see `containers/BUILDING.md`; note `build-packages-for-obs`/`push-packages-to-obs`
are external developer tools, not scripts shipped here). For local package builds the
repo ships the `osc build` wrapper `rel-eng/build-packages-local-obs.sh`.

## Gotchas

- Changelog policy: never edit the master `<pkg>.changes` file; add a fragment named
  `<pkg>.changes.<author>.<topic>` beside it — enforced by
  `.github/workflows/changelogs/changelogs.py`.
- Server-image Dockerfiles pin stable UIDs/GIDs (salt/tomcat/www/tftp) and must stay in
  sync with packaging (`containers/server-image/Dockerfile.uyuni`).

## Pointers

- `containers/doc/rds.sh` — SQL that a DBA must run to create mandatory report-DB
  objects the deployment user lacks permissions for.
- `containers/mlm-supportconfig/` — supportconfig helper for Kubernetes
  installations: fetched from GitHub via HTTPS and executed on the node.
- `rel-eng/AGENTS.md` for release tooling.
