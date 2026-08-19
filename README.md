# LifeOS Engineering Platform

LifeOS is a Java 25, AI-powered, cross-platform personal operating system and engineering lab. It demonstrates modern Java concurrency, microservices, GraphQL, gRPC, event-driven architecture, advanced algorithms, video streaming, blockchain verification, SQL/NoSQL data modeling, observability, and production-grade architecture.

The project is designed to showcase senior-level engineering ability and FAANG-style system design skills through real product use cases, architecture decision records, algorithm documentation, benchmark results, and scalable distributed-system patterns.

> A Java 25 distributed systems platform designed to demonstrate FAANG-level software engineering, advanced algorithms, AI orchestration, blockchain verification, structured concurrency, and production-scale architecture.

## Status

Early-stage. Twelve bounded backend service modules are implemented and independently packageable,
but this is not a production-deployment claim: several target capabilities deliberately fail closed
until their external dependencies are reviewed and provisioned. See
[`docs/architecture/current-state.md`](docs/architecture/current-state.md) for the precise
built-versus-planned breakdown.

## What's Actually Built

* **identity-service** — account registration, first-party email/password login, configured OAuth2/OIDC authorization-code login, passkey/WebAuthn registration/assertion login, one-time recovery-code login, short-lived JWT/JWKS validation, one-time refresh-token rotation, and deterministic RBAC/ABAC policy decisions over PostgreSQL, with Redis-backed rate limiting and single-use callback state. See [`docs/api/identity-service.md`](docs/api/identity-service.md) and [`docs/diagrams/identity-authorization.md`](docs/diagrams/identity-authorization.md).
* **task-goal-service** — authenticated owner/tenant-scoped Task and Goal lifecycles, durable
  idempotency, persisted mixed dependencies, and bounded deterministic execution ordering over
  PostgreSQL. See [`docs/api/task-goal-service.md`](docs/api/task-goal-service.md).
* **gateway-service** — one public REST ingress for finite, deployment-configured route prefixes.
  It enforces Redis-backed budgets, bounded timeouts, bulkheads/circuits, authentication, and a
  validated `X-Correlation-ID`. See [`docs/api/gateway-service.md`](docs/api/gateway-service.md).
* **profile, notification, calendar, finance, trust-ledger, document-vault, media, AI assistant,
  and analytics services** — bounded domain foundations with their exact implemented/pending scope in
  [`docs/architecture/current-state.md`](docs/architecture/current-state.md) and `docs/api/`.
* Local dev infrastructure (PostgreSQL + Redis via `infrastructure/docker-compose/`, plus optional
  Kafka, observability, and loopback-only Besu blockchain profiles) — copy
  [`infrastructure/docker-compose/.env.example`](infrastructure/docker-compose/.env.example) to a
  gitignored `.env` and supply local-only database credentials before starting it.
  The blockchain profile and digest-only contract are documented in
  [`docs/operations/local-blockchain.md`](docs/operations/local-blockchain.md).

Complete gRPC client-mesh migration, production Kafka/provider/ledger operations, full
client domain workflows, and a production observability deployment remain planned. Web, JavaFX, and
Flutter client shells are present under `clients/`, share the core information architecture, and use bounded gateway contracts. A local opt-in
Collector/Prometheus/Loki/Tempo/Grafana reference profile is available via the
[observability runbook](docs/operations/observability.md). The current internal authorization
adapter is bounded and workload-authenticated; deployment-managed upstream connections still need
a production mTLS rollout.

## Target Feature Set

The full product vision (not all built yet — see Status above):

* Personal dashboard
* Goal planning
* Task management
* Calendar optimization
* Habit tracking
* Budgeting and financial intelligence
* Personal document vault
* AI assistant
* Video journaling and coaching sessions
* Personal knowledge graph
* AI-powered search
* Blockchain-backed document verification
* Analytics and recommendations
* Cross-platform access from web, desktop, and mobile

## Technology Stack

| Layer             | Technology                                  |
| ----------------- | ------------------------------------------- |
| Backend           | Java 25 + Spring Boot                       |
| Desktop           | JavaFX + GraalVM Native Image               |
| Mobile            | Flutter for iOS and Android                 |
| Web               | Angular                                     |
| APIs              | REST + GraphQL + gRPC                       |
| Events            | Kafka or Apache Pulsar                      |
| SQL Database      | PostgreSQL                                  |
| NoSQL Database    | MongoDB                                     |
| Cache             | Redis                                       |
| Search            | OpenSearch or Meilisearch                   |
| Vector Database   | Qdrant or pgvector                          |
| Object Storage    | MinIO or S3                                 |
| Video             | WebRTC + HLS                                |
| Blockchain        | Web3j + Hyperledger Besu                    |
| Observability     | OpenTelemetry + Prometheus + Grafana + Loki |
| Infrastructure    | Docker + Kubernetes + Terraform             |
| CI/CD             | GitHub Actions                              |

## Documentation

* `REQUIREMENTS.md` — product vision, architecture, technology decisions, and roadmap (source of truth for all development). Intentionally gitignored — a fresh clone won't have it, and it isn't needed to build/run/test the code. Its substance is mirrored across the tracked docs below (ADRs, interview docs, architecture doc) and this README; the source document itself requires the project owner.
* [AGENTS.md](AGENTS.md) / [CLAUDE.md](CLAUDE.md) — engineering standards and required workflow for contributors and AI coding agents
* `docs/adr/` — architecture decision records covering major technology and architecture choices
* `docs/algorithms/`, `docs/api/`, `docs/architecture/`, `docs/concurrency/`, `docs/diagrams/`, `docs/interview/`, `docs/benchmarks/` — algorithm write-ups, API docs, current-state architecture, and interview-prep docs for what's actually built so far (each one explicitly distinguishes built vs. planned); `docs/benchmarks/` records only dated, reproducible measurements and keeps unrun targets clearly marked

## Roadmap

The full 8-phase roadmap — from foundation and core algorithms through microservices, AI, video streaming, blockchain, desktop/mobile clients, and production readiness — lives in `REQUIREMENTS.md`'s "Suggested MVP Roadmap" section (see the Documentation section above for why that file isn't linked directly).

## Verification

Run the repository checks with:

```bash
./gradlew --no-daemon check
git diff --check
bash scripts/verify-observability-stack.sh
```

The Gradle build covers the currently implemented services; the observability script statically
validates the local telemetry profile's YAML, JSON, and topology invariants without Docker or
service secrets. For a local Compose configuration check, copy
`infrastructure/docker-compose/.env.example` to the ignored `.env` file, fill the required local
database values, then run:

```bash
docker compose -f infrastructure/docker-compose/docker-compose.yml config -q
```

See the [observability runbook](docs/operations/observability.md) for the optional profile and its
explicitly local-only boundary.
