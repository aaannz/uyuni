# `branding/` — brand-specific resources for the Java server

Loaded when working in this subtree; root `AGENTS.md` still applies.

## Scope and ownership

Maven jar module `org.uyuni-project:branding`, child of `uyuni-java-parent`, jar renamed
`java-branding`. Contents are currently minimal: a placeholder string class and an XLIFF
string resource.

## Why java/ depends on it

- `org.uyuni-project:branding` is a managed dependency of `spacewalk-java` and a direct
  dependency of `java/core`; the Ant build also compiles `branding/src/main/java` into
  `java-branding.jar` via the `refresh-branding-jar` target of `java/manager-build.xml`,
  and the Ant `test` target depends on it.
- RPM `spacewalk-branding` is required by `spacewalk-java`, `spacewalk-web`, and
  `spacewalk-reports`.

## Build and test commands

    mvn -f branding install

Must succeed before `java/` builds resolve the `branding` artifact.

## Gotchas

- In a fresh checkout, building `java/` without installing `branding/` first fails on the
  dependency above.

## Pointers

- `java/AGENTS.md` — build order including this module.
