# Architecture — Current State

This document describes what is actually built and running today. For the full target architecture, see `REQUIREMENTS.md`'s "High-Level Architecture" and "Core Microservices" sections. For a diagram, see [`docs/diagrams/current-architecture.md`](../diagrams/current-architecture.md).

## What exists

A Gradle multi-module monorepo (`settings.gradle.kts`) with a Java 25 toolchain, containing three independently deployable Spring Boot 3.5.16 services:

- **`services/gateway-service`** (port 8080; management port 9080) — the public REST ingress. It resolves only deployment-configured versioned route prefixes, enforces Redis-backed route/client budgets, isolates each upstream with a bounded bulkhead and circuit breaker, forwards bounded HTTP requests to fixed origins, and propagates one validated `X-Correlation-ID`; see [`docs/api/gateway-service.md`](../api/gateway-service.md).

- **`services/identity-service`** (port 8081) — account registration, first-party email/password login, configured OAuth2/OIDC authorization-code login, passkey/WebAuthn assertion login, short-lived JWT/JWKS validation, one-time refresh-token rotation, and deterministic RBAC/ABAC policy decisions over its own PostgreSQL database (`lifeos_identity`). Passkey credential registration/provisioning and user-facing session management remain planned; see [`docs/api/identity-service.md`](../api/identity-service.md).
- **`services/task-goal-service`** (port 8082) — authenticated owner/tenant-scoped goal create/list/read plus a topological-sort dependency-ordering endpoint, over its own PostgreSQL database (`lifeos_task_goal`). It validates sessions and enforces identity authorization decisions before object access; see [`docs/api/task-goal-service.md`](../api/task-goal-service.md).

Each service owns its own database rather than sharing one — this is the per-service-schema decision from [ADR-008](../adr/ADR-008-use-postgresql-as-system-of-record.md), applied from day one rather than retrofitted later.

The `contracts/grpc-contracts` module also exists with versioned protobuf definitions and
Gradle-generated Java gRPC stubs for the planned internal RPC surface. No production service
endpoint or service-mesh mTLS path consumes those stubs yet.

`infrastructure/docker-compose/docker-compose.yml` brings up PostgreSQL (with an init script creating both service databases) and Redis for local development.

## What is deliberately not built yet

- **No GraphQL or production gRPC endpoints** — the gateway and both domain services expose REST. Story 1.6 still uses a deliberately narrow, workload-authenticated, bounded internal REST adapter for validation and authorization decisions because service adoption of the ADR-007 contracts and the production mTLS rollout remain pending. GraphQL and production gRPC layers ([ADR-006](../adr/ADR-006-use-graphql-for-dashboard-aggregation.md), [ADR-007](../adr/ADR-007-use-grpc-for-internal-communication.md)) remain planned.
- **No event bus** — no Kafka/Pulsar, no outbox pattern implementation ([ADR-016](../adr/ADR-016-use-event-driven-architecture.md), [ADR-017](../adr/ADR-017-use-outbox-pattern.md) describe the target design). Both services are purely synchronous today.
- **Redis is used by gateway and identity-service rate limiting, OIDC callback state, and WebAuthn challenge state** — it stores bounded digest/HMAC-keyed counters and short-lived, single-use authorization/assertion state. Durable accounts, credentials, sessions, refresh families, consumed-token replay evidence, and external-identity mappings remain in PostgreSQL; Redis is not the refresh correctness authority and admission/authentication failures fail closed.
- **Authentication and authorization are partial** — identity-service stores Argon2id password credentials, WebAuthn public-key metadata/counters, verified provider-subject mappings, durable token families, authorization memberships, and replay evidence; issues bounded access JWTs and opaque refresh tokens through the shared session authority; publishes configured public JWKS; validates JWTs plus durable session state; and returns generic failures. It evaluates deterministic role and owner/tenant policy decisions for the Task/Goal slice, which fail closed and create redacted audit outcomes. Passkey credential registration, key rotation windows, production gRPC/mTLS contracts, and user-facing session listing/revocation remain planned stories; gateway authentication enforcement is implemented.
- **No central observability stack yet** — identity-service now emits ECS-structured stdout logs, Micrometer/Prometheus metrics, and OpenTelemetry traces, and exposes liveness/readiness probes on its private management listener (`9081`, loopback by default). Prometheus/Grafana/Loki/Tempo collection and dashboards are not deployed yet, and task-goal-service has not received the same instrumentation.
- **No clients** — no Angular web app, no JavaFX desktop app, no Flutter mobile app. Both services have been exercised only via direct HTTP calls (curl / manual verification).
- **GitHub Actions CI is deployed** — [`.github/workflows/ci.yml`](../../.github/workflows/ci.yml) compiles and tests the services on JDK 25 for pull requests and pushes to `dev` and `main`.

## Why this order

The gateway is the first cross-service edge in the roadmap: it creates one stable public contract while keeping service ownership and the existing bounded internal authorization adapter intact. Stories 2.1–2.3 now provide routing, authentication enforcement, Redis-backed rate limiting, and dependency isolation; the central observability stack remains a later deployment concern.
