# `microservices/` — shared Maven parent, common library, CoCo attestation

Loaded when working in this subtree; root `AGENTS.md` still applies.

## Scope and ownership

Root of the Java Maven reactor: `org.uyuni-project:uyuni-java-parent`, "Parent POM for
all Uyuni Maven components". The parent POM's modules reach outside this directory
(`uyuni-java-common`, `coco-attestation`, `../../branding`, `../../java`) — `java/` and
`branding/` declare it as their parent.

## Directory layout

- `uyuni-java-parent/` — parent POM only: shared dependencyManagement (log4j, mybatis,
  postgresql driver, c3p0, junit, mockito) and pluginManagement: compiler release 17,
  surefire settings, checkstyle pointed at `java/buildconf/checkstyle.xml`.
- `uyuni-java-common/` — `com.suse.common` library (concurrent, configuration, database,
  io, security); consumed only by the coco-attestation modules here.
- `coco-attestation/` — 5 modules (`attestation-core`, `attestation-module`,
  `-snpguest`, `-secureboot`, `-pvattest`); packaged as the Confidential Computing
  Attestation utility.

## Build and test commands

Quoted from `.github/workflows/java-microservices-mvn-build.yml` (JDK 17):

    mvn -B --no-transfer-progress --file microservices/uyuni-java-parent/pom.xml --non-recursive install checkstyle:check
    mvn -B --no-transfer-progress --file microservices/uyuni-java-common/pom.xml install checkstyle:check
    mvn -B --no-transfer-progress --file microservices/coco-attestation/pom.xml install checkstyle:check

## Gotchas

- Despite the name, this directory hosts no "microservice" servers besides the
  attestation daemon; it is primarily the shared parent + libs.
- `uyuni-java-common` is NOT a dependency of `java/core` or `java/webapp` (absent from
  both poms); do not assume `java/` uses it.
- Version pins across `java/`, `branding/` and these poms are bumped manually in
  lockstep — no in-repo automation touches pom versions (`rel-eng/push-version` only
  rewrites `.spec` files).

## How java/ integrates with the attestation daemon (DB-mediated only)

- No code dependency in either direction. Shared tables:
  `schema/spacewalk/common/tables/suseCoCoAttestationResult.sql` (+ Report/Config tables).
- The Java server creates result rows with status REQUESTED via Hibernate
  (`java/core/src/main/java/com/suse/manager/model/attestation/AttestationFactory.java`);
  the daemon claims REQUESTED/PENDING rows via MyBatis, notified by PG
  `LISTEN pendingAttestationResult`
  (`schema/spacewalk/postgres/triggers/suseCoCoAttestationResult.sql`), computes
  `in_data`, then verifies and sets SUCCEEDED/FAILED + `attested`.
- The server merges `in_data` into a salt `state.apply`, writes `out_data`, sets results
  PENDING, and reads final status from the same tables; a DB trigger rolls result
  statuses up into the report row. Mapping SQL:
  `microservices/coco-attestation/attestation-core/src/main/resources/mappers/attestation-result.xml`.

## Pointers

- Attestation daemon classes: `microservices/coco-attestation/attestation-core/src/main/java/com/suse/coco/`.
- Java server build: `java/AGENTS.md`.
