# `java/` — Uyuni server: web UI, business layer, XML-RPC API, Taskomatic

Loaded when working in this subtree; root `AGENTS.md` and `AGENTS_CUSTOM.md` still apply.

## Scope and ownership

The Uyuni/SUSE Manager server itself: legacy Struts/JSP UI, the Spark-based custom
web UI backend, the public XML-RPC API, Hibernate domain layer, and the Taskomatic
scheduler. Maven multi-module project `org.uyuni-project:spacewalk-java`, child of
`org.uyuni-project:uyuni-java-parent` (see `microservices/AGENTS.md`). Modules:
`doclets`, `core` (jar), `webapp` (war). RPM `spacewalk-java` is still built with
Ant, not Maven (packaging instructions in `java/spacewalk-java.spec`).

## Directory layout

- `java/core/src/main/java/com/redhat/rhn/` — legacy namespace: `common/` (config, db,
  hibernate), `domain/` (Hibernate entities/factories), `manager/` (business services),
  the `frontend` package tree (`com.redhat.rhn.frontend.action` for Struts,
  `com.redhat.rhn.frontend.xmlrpc` for XML-RPC handlers, `com.redhat.rhn.frontend.servlets`
  for servlets), `taskomatic/`, `webapp/`.
- `java/core/src/main/java/com/suse/manager/` — newer namespace: `webui/` (Spark backend:
  Router, controllers, services, websocket), `reactor/` (Salt reactor, PG listener),
  `attestation/`, `xmlrpc/` (newer handlers: admin, iss, maintenance).
- `java/core/src/test/java/` — JUnit tests mirroring main packages.
- `java/webapp/src/main/webapp/` — JSPs plus `WEB-INF/` deployment config
  (`java/webapp/src/main/webapp/WEB-INF/web.xml`, `struts-config.xml`);
  `java/webapp/src/apidoc/` — API doc templates.
- `java/buildconf/` — Ant imports/props, checkstyle config, ivy config, test config;
  `java/scripts/` — helpers including `maven/` deploy tooling.
- `java/repository/` and `java/.obs-to-maven-cache/` — generated local Maven repo fed from
  OBS; do not edit.

## Entry points

- Webapp listener (boots Hibernate, message queue, SaltReactor, Prometheus exporter):
  `java/core/src/main/java/com/redhat/rhn/webapp/RhnServletListener.java`, wired in
  `java/webapp/src/main/webapp/WEB-INF/web.xml`.
- XML-RPC API: `com.redhat.rhn.frontend.xmlrpc.XmlRpcServlet` mapped to `/rpc/api/*`;
  handlers in the `com.redhat.rhn.frontend.xmlrpc` package tree plus newer ones in the
  `com.suse.manager.xmlrpc` package; registered by namespace in
  `java/core/src/main/java/com/redhat/rhn/frontend/xmlrpc/HandlerFactory.java`, dispatched
  reflectively in `BaseHandler`. API docs are generated from `@apidoc.*` javadoc tags.
- Custom web UI backend: Spark — `com.suse.manager.webui.Router` registers all controller
  routes (declared as `applicationClass` of `SparkFilter` in `web.xml`).
- Legacy Struts UI: `ActionServlet` mapped to `*.do`.
- Taskomatic: standalone `main()` in
  `java/core/src/main/java/com/redhat/rhn/taskomatic/core/TaskomaticDaemon.java`.
- Startup order: `RhnServletListener.contextInitialized()` (creates the Hibernate
  SessionFactory, message queue, SaltReactor) always runs before `SparkFilter`
  instantiates `Router` — guaranteed by the servlet lifecycle (listeners finish before
  filters init), not by any web.xml ordering knob. Spark's session filter commits via
  the same `HibernateFactory`
  (`java/core/src/main/java/com/suse/manager/webui/utils/SparkApplicationHelper.java`).

## Build and test commands

Local Maven build order (branding and parent must install first):

    mvn -f microservices/uyuni-java-parent --non-recursive install
    mvn -f branding install
    mvn -f java --non-recursive initialize   # fills the OBS-backed local repo (java/pom.xml)
    mvn -f java package

- Tests are skipped by default because they need a database (`java/pom.xml`).
- Start the PostgreSQL test container first (from `java/`, per `java/Makefile.docker`):
  `cp buildconf/test/rhn.conf.postgresql-example buildconf/test/rhn.conf`, then
  `make -f Makefile.docker` (runs the `uyuni-master-pgsql-4eclipse` image on localhost:5432).
- Focused tests: `mvn -f java/core test -DskipTests=false -Dtest=<TestClass>`
- CI checkstyle uses Ant, not Maven:
  `ant -f java/manager-build.xml ivy` then `ant -f java/manager-build.xml checkstyle`
  (`.github/workflows/java-checkstyle.yml`). The Ant toolchain still matters: see targets in
  `java/manager-build.xml` (compile, webapp, test, checkstyle, refresh-branding-jar).
- CI builds and deploys the server code with Ant inside the acceptance-test
  environment: `testsuite/podman_runner/09_build_server_code.sh` runs
  `ant -f manager-build.xml ivy` (with retry), then `refresh-branding-jar deploy`,
  `apidoc-jsp`, builds the frontend (`npm --prefix web run build -- --check-spec=false`)
  and copies salt/susemanager files into the running server container.

## Gotchas

- `java/README` is stale: it names `Makefile`, `ivy.xml`, `rhn-java.spec`, none of which
  exist — trust the poms and the spec.
- Unit tests need a live PostgreSQL on localhost:5432 AND
  `java/buildconf/test/rhn.conf`, which the `Makefile.docker` flow creates.
- Most dependencies come from OBS into a local `file://` Maven repo (`use-obs-to-maven`
  profile in `java/pom.xml`); the first build must run the `initialize` phase.
- Any plain `mvn ... package` silently skips tests.
- `target/` dirs under `java/core`, `java/webapp`, `java/doclets` are build output.

## Pointers

- XML-RPC handler conventions: `java/core/src/main/java/com/redhat/rhn/frontend/xmlrpc/BaseHandler.java`;
  XML-RPC work against a deployed server: `AGENTS_XMLRPC.md`.
- API doc generation: `java/doclets/src/main/java/com/redhat/rhn/internal/doclet/`.
- Branding dependency: `branding/AGENTS.md`.
- CI: `.github/workflows/java-checkstyle.yml`, `.github/workflows/java-microservices-mvn-build.yml`,
  `.github/workflows/sonarcloud.yml`, `.github/workflows/changelogs.yml`.
