# LifeOS Engineering Platform

LifeOS is a Java 25, AI-powered, cross-platform personal operating system and engineering lab. It demonstrates modern Java concurrency, microservices, GraphQL, gRPC, event-driven architecture, advanced algorithms, video streaming, blockchain verification, SQL/NoSQL data modeling, observability, and production-grade architecture.

The project is designed to showcase senior-level engineering ability and FAANG-style system design skills through real product use cases, architecture decision records, algorithm documentation, benchmark results, and scalable distributed-system patterns.

> A Java 25 distributed systems platform designed to demonstrate FAANG-level software engineering, advanced algorithms, AI orchestration, blockchain verification, structured concurrency, and production-scale architecture.

## Status

Early-stage / planning. The product vision, architecture, and roadmap are defined in [REQUIREMENTS.md](REQUIREMENTS.md); implementation has not started yet.

## What It Does

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

* [REQUIREMENTS.md](REQUIREMENTS.md) — product vision, architecture, technology decisions, and roadmap (source of truth for all development)
* [AGENTS.md](AGENTS.md) / [CLAUDE.md](CLAUDE.md) — engineering standards and required workflow for contributors and AI coding agents
* `docs/adr/` — architecture decision records (to be populated per [REQUIREMENTS.md](REQUIREMENTS.md))
* `docs/algorithms/`, `docs/interview/`, `docs/benchmarks/` — algorithm write-ups, interview-prep docs, and benchmark results (to be populated per [REQUIREMENTS.md](REQUIREMENTS.md))

## Roadmap

See [REQUIREMENTS.md](REQUIREMENTS.md#suggested-mvp-roadmap) for the full 8-phase roadmap, from foundation and core algorithms through microservices, AI, video streaming, blockchain, desktop/mobile clients, and production readiness.
