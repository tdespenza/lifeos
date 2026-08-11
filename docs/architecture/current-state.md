# Architecture — Current State

This document describes what is actually built and running today. For the full target architecture, see `REQUIREMENTS.md`'s "High-Level Architecture" and "Core Microservices" sections. For a diagram, see [`docs/diagrams/current-architecture.md`](../diagrams/current-architecture.md).

## What exists

A Gradle multi-module monorepo (`settings.gradle.kts`) with a Java 25 toolchain, containing two independently deployable Spring Boot 3.5.16 services:

- **`services/identity-service`** (port 8081) — account registration, first-party email/password login, configured OAuth2/OIDC authorization-code login, and passkey/WebAuthn assertion login over its own PostgreSQL database (`lifeos_identity`). Passkey credential registration/provisioning, refresh-token rotation, RBAC/ABAC, and user-facing session management remain planned; see [`docs/api/identity-service.md`](../api/identity-service.md).
- **`services/task-goal-service`** (port 8082) — goal CRUD plus a topological-sort dependency-ordering endpoint, over its own PostgreSQL database (`lifeos_task_goal`); see [`docs/api/task-goal-service.md`](../api/task-goal-service.md).

Each service owns its own database rather than sharing one — this is the per-service-schema decision from [ADR-008](../adr/ADR-008-use-postgresql-as-system-of-record.md), applied from day one rather than retrofitted later.

`infrastructure/docker-compose/docker-compose.yml` brings up PostgreSQL (with an init script creating both service databases) and Redis for local development.

## What is deliberately not built yet

- **No API Gateway, no GraphQL, no gRPC** — both services are called directly over plain REST today. The gateway/GraphQL/gRPC layers ([ADR-006](../adr/ADR-006-use-graphql-for-dashboard-aggregation.md), [ADR-007](../adr/ADR-007-use-grpc-for-internal-communication.md)) only earn their complexity once there's more than one service worth aggregating or calling internally.
- **No event bus** — no Kafka/Pulsar, no outbox pattern implementation ([ADR-016](../adr/ADR-016-use-event-driven-architecture.md), [ADR-017](../adr/ADR-017-use-outbox-pattern.md) describe the target design). Both services are purely synchronous today.
- **Redis is used by identity-service login rate limiting, OIDC callback state, and WebAuthn challenge state** — it stores bounded HMAC-keyed attempt counters and short-lived, single-use authorization/assertion state. Durable accounts, credentials, sessions, and external-identity mappings remain in PostgreSQL; Redis failures fail closed for authentication. No shared cache, refresh-token rotation, or gateway rate limiting is implemented yet.
- **Authentication is partial** — identity-service now stores Argon2id password credentials, WebAuthn public-key metadata/counters, and verified provider-subject mappings; issues short-lived HS256 access tokens through the shared session authority; persists session/token digests; and returns generic failures. Passkey credential registration, asymmetric key/JWKS rotation, refresh tokens, RBAC/ABAC, gateway enforcement, and user-facing session listing/revocation remain planned stories.
- **No central observability stack yet** — identity-service now emits ECS-structured stdout logs, Micrometer/Prometheus metrics, and OpenTelemetry traces, and exposes liveness/readiness probes on its private management listener (`9081`, loopback by default). Prometheus/Grafana/Loki/Tempo collection and dashboards are not deployed yet, and task-goal-service has not received the same instrumentation.
- **No clients** — no Angular web app, no JavaFX desktop app, no Flutter mobile app. Both services have been exercised only via direct HTTP calls (curl / manual verification).
- **No CI enforcement yet** — a GitHub Actions workflow exists (compile + test on JDK 25) but is not yet pushed to the repository, blocked on a GitHub App permission scope unrelated to the code itself.

## Why this order

This follows Phase 1 of `REQUIREMENTS.md`'s "Suggested MVP Roadmap": establish the monorepo, a real Java 25 + Spring Boot baseline, and one or two working services against a real database before adding cross-cutting concerns (gateway, events, observability) that only pay off once there's more than one moving part to coordinate.
