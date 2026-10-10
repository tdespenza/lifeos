# Current Architecture Diagram

This reflects what is actually running today, not the full target architecture in `REQUIREMENTS.md`'s "High-Level Architecture" section. See [`docs/architecture/current-state.md`](../architecture/current-state.md) for the narrative version and the gap to the target design.

```mermaid
graph TD
    subgraph Client["Client (manual / curl today — no UI yet)"]
        C[HTTP client]
    end

    subgraph Services["Backend — Java 25 + Spring Boot 3.5.16"]
        G["gateway-service :8080<br/>allow-listed REST routing + correlation"]
        I["identity-service :8081<br/>authentication + authorization decisions"]
        T["task-goal-service :8082<br/>authenticated goal create/list/read + dependency-order"]
    end

    subgraph Data["docker-compose"]
        PG[("PostgreSQL 17<br/>lifeos_identity db")]
        PG2[("PostgreSQL 17<br/>lifeos_task_goal db")]
        R[("Redis 8<br/>limits + OIDC/WebAuthn state")]
    end

    C -->|REST/JSON| G
    G -->|/api/v1/accounts + /api/v1/auth| I
    G -->|/api/v1/goals| T
    T -->|bounded internal REST:<br/>validate + authorize| I
    I --> PG
    I -.->|atomic limits + short-lived state| R
    T --> PG2

    style R fill:#eee,stroke:#999,stroke-dasharray: 5 5
```

Redis is drawn dashed because it stores bounded, short-lived authentication/workload-rate-limit
state rather than the durable identity store; accounts, credentials, sessions, memberships, and
audit records remain in PostgreSQL. The internal Task/Goal-to-Identity REST adapter is a bounded,
workload-authenticated transition while ADR-007's versioned contract module exists but its
production gRPC/mTLS platform path is not yet built.
See [`why-redis.md`](../interview/why-redis.md).

## Target architecture (not yet built)

`REQUIREMENTS.md`'s "Core Microservices" section names 13 services total; Gateway, Identity, and Task/Goal are the 3 built so far, leaving 10 not yet started: Profile, Calendar, Finance, Document Vault, Media Streaming, AI Orchestrator, Algorithm Engine, Blockchain Trust Ledger, Notification, and Analytics. (A `search-service/` also appears in REQUIREMENTS.md's high-level directory-tree diagram, but it isn't one of the 13 named in the Core Microservices section itself, so it's not counted here.) The event bus (Kafka/Pulsar), GraphQL, production gRPC endpoints/mTLS, and the Angular/JavaFX/Flutter clients are not implemented yet; the versioned gRPC contract module exists as a build artifact. This diagram will be extended as each phase of `REQUIREMENTS.md`'s "Suggested MVP Roadmap" lands, rather than drawn speculatively ahead of the code.
