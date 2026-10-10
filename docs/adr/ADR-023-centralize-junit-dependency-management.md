# ADR-023: Centralize JUnit dependency management

## Context

LifeOS contains several Java projects, including contract libraries and Spring Boot services. Each
project needs a consistent JUnit Platform and Jupiter dependency set so that tests run with the
same API, engine, and security fixes. Independently pinning JUnit artifacts in modules allows
version drift and can leave one module behind when a vulnerability or compatibility fix is
released.

## Decision Made

The root Gradle build owns the JUnit BOM version and imports that BOM for every Java project. Test
dependencies declare JUnit artifacts without independent versions, and the root build supplies the
JUnit Platform launcher required by Gradle. The current BOM version is `5.13.2`.

This policy applies to all Java projects in the repository, including `contracts/*` and
`services/*`. A module may not override the JUnit BOM or pin a JUnit artifact independently unless
this ADR is explicitly revised.

## Rationale

Central management keeps Jupiter, the platform, and related test artifacts aligned across module
boundaries. It also gives security and maintenance upgrades one auditable change point while
preserving each module's ability to choose the test APIs it needs.

## Update Policy

Update `junitBomVersion` in the root `build.gradle.kts`, run the full repository `check`, inspect
dependency resolution for every Java project, and update this ADR when the supported baseline
changes. Security fixes take priority over waiting for a broader dependency refresh; the change
must still preserve a single BOM-managed version across all modules.
