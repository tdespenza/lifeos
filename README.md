# LifeOS Engineering Platform

LifeOS is a Java 25, AI-powered, cross-platform personal operating system and engineering lab. It demonstrates modern Java concurrency, microservices, GraphQL, gRPC, event-driven architecture, advanced algorithms, video streaming, blockchain verification, SQL/NoSQL data modeling, observability, and production-grade architecture.

The project is designed to showcase senior-level engineering ability and FAANG-style system design skills through real product use cases, architecture decision records, algorithm documentation, benchmark results, and scalable distributed-system patterns.

> A Java 25 distributed systems platform designed to demonstrate FAANG-level software engineering, advanced algorithms, AI orchestration, blockchain verification, structured concurrency, and production-scale architecture.

## Status

Early-stage. Phase 1 of the roadmap is underway: two backend services are built and running (see below), with the rest of the target architecture still ahead. See [`docs/architecture/current-state.md`](docs/architecture/current-state.md) for a precise built-vs-planned breakdown.

## What's Actually Built

* **identity-service** — account registration over PostgreSQL. See [`docs/api/identity-service.md`](docs/api/identity-service.md).
* **task-goal-service** — goal CRUD plus a topological-sort dependency-ordering endpoint (Kahn's algorithm) over PostgreSQL. See [`docs/api/task-goal-service.md`](docs/api/task-goal-service.md) and [`docs/algorithms/topological-sort-goal-dependencies.md`](docs/algorithms/topological-sort-goal-dependencies.md).
* Local dev infrastructure (PostgreSQL + Redis via `infrastructure/docker-compose/`) — Redis isn't used by any service yet.

No authentication, no other services, no clients (web/desktop/mobile), no event bus, no observability stack. See `CONTRIBUTING.md` (once merged — see #14) to build and run this yourself.

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
* `docs/adr/` — 18 architecture decision records covering the major technology/architecture choices
* `docs/algorithms/`, `docs/api/`, `docs/architecture/`, `docs/concurrency/`, `docs/diagrams/`, `docs/interview/`, `docs/benchmarks/` — algorithm write-ups, API docs, current-state architecture, and interview-prep docs for what's actually built so far (each one explicitly distinguishes built vs. planned); `docs/benchmarks/` is a plan only — no numbers until something's actually been measured

## Roadmap

See `REQUIREMENTS.md`'s "Suggested MVP Roadmap" section for the full 8-phase roadmap, from foundation and core algorithms through microservices, AI, video streaming, blockchain, desktop/mobile clients, and production readiness.
