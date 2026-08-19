# Current Architecture Diagram

This diagram reflects repository-verified implementation, not a production deployment claim. See
[the current-state narrative](../architecture/current-state.md) for precise implemented-versus-pending
scope.

```mermaid
graph TD
    subgraph Client["Clients (bounded shells; full workflows pending)"]
        C["HTTP client"]
    end

    subgraph Public["Public ingress"]
        G["gateway-service :8080<br/>allow-listed REST, auth, rate limits, correlation"]
    end

    subgraph Services["Java 25 + Spring Boot service modules"]
        I["identity-service :8081<br/>sessions + V1/V2 authorization"]
        T["task-goal-service :8082<br/>tasks, goals, dependencies"]
        P["profile-service :8083<br/>profile + household"]
        N["notification-service :8084<br/>SSE + durable delivery"]
        C1["calendar-service :8085<br/>events, blocks, reminders"]
        F["finance-service :8086<br/>budgets + immutable postings"]
        L["trust-ledger-service :8087<br/>hash + Merkle proofs only"]
        D["document-vault-service :8088<br/>metadata + local object reference"]
        M["media-service :8089<br/>asset/session control plane only"]
        A["ai-assistant-service :8090<br/>bounded fail-closed interaction foundation"]
    end

    subgraph State["Local development state"]
        PI[("lifeos_identity")]
        PT[("lifeos_task_goal")]
        PP[("lifeos_profile")]
        PN[("lifeos_notification")]
        PC[("lifeos_calendar")]
        PF[("lifeos_finance")]
        PD[("lifeos_document_vault")]
        PM[("lifeos_media")]
        PA[("lifeos_ai_assistant")]
        R[("Redis 8<br/>bounded ephemeral state")]
        K[["Kafka KRaft<br/>optional local eventing profile"]]
    end

    C -->|"REST/JSON or multipart"| G
    G -->|"Identity routes"| I
    G -->|"Task/Goal routes"| T
    G -->|"Profile routes"| P
    G -->|"Notification + SSE routes"| N
    G -->|"Calendar routes"| C1
    G -->|"Finance routes"| F
    G -->|"Document routes<br/>exact create request relay"| D
    G -->|"Media routes<br/>exact upload/HLS relays"| M
    G -->|"AI Assistant routes<br/>bounded JSON/GET"| A

    T -->|"bounded workload REST"| I
    P -->|"bounded workload REST"| I
    N -->|"bounded workload REST"| I
    C1 -->|"bounded workload REST"| I
    F -->|"bounded workload REST"| I
    L -->|"bounded workload REST"| I
    D -->|"bounded workload REST"| I
    M -->|"bounded workload REST"| I
    A -->|"bounded workload REST"| I

    I --> PI
    I -.-> R
    T --> PT
    P --> PP
    N --> PN
    C1 --> PC
    F --> PF
    D --> PD
    M --> PM
    A --> PA
    C1 -->|"transactional outbox<br/>NotificationRequestedV2"| K
    K -->|"durable inbox consumer"| N

    style R fill:#eee,stroke:#999,stroke-dasharray: 5 5
    style K fill:#eee,stroke:#999,stroke-dasharray: 5 5
```

The dashed Redis and Kafka nodes are optional, localhost-bound development infrastructure rather
than durable product source-of-truth databases. Redis carries bounded rate-limit/authentication
state; Kafka currently carries the narrow Calendar-to-Notification CloudEvents path. PostgreSQL is
the system of record for each shown stateful bounded context.

Trust Ledger has no public gateway prefix yet: its bounded proof APIs can be exercised directly in
the current foundation, but are not a claim of a production perimeter. It deliberately reports no
Besu/Web3j anchor as complete. Document Vault's production object-store adapter also remains
unimplemented and fails closed; the local object reference in the diagram is not a production
object-store deployment. Media's public asset/session control plane is gateway-routed, but its
production object-store, HLS worker, and WebRTC/SFU adapters intentionally fail closed. AI
Assistant has a public gateway prefix but deliberately fails closed without a reviewed model
provider and registered Identity workload token; it is not a live AI/RAG deployment.

The following remain outside the current diagram: Analytics, GraphQL, live gRPC/mTLS transport,
production Kafka/provider/ledger operations, and Angular/JavaFX/Flutter
clients. They are target architecture or work-in-progress modules, not verified deployed paths.
