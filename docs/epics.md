---
stepsCompleted: [1, 2]
inputDocuments:
  - REQUIREMENTS.md
  - docs/adr/ADR-001-use-java-25.md
  - docs/adr/ADR-002-use-virtual-threads.md
  - docs/adr/ADR-003-use-structured-concurrency.md
  - docs/adr/ADR-004-use-scoped-values.md
  - docs/adr/ADR-005-use-spring-boot-microservices.md
  - docs/adr/ADR-006-use-graphql-for-dashboard-aggregation.md
  - docs/adr/ADR-007-use-grpc-for-internal-communication.md
  - docs/adr/ADR-008-use-postgresql-as-system-of-record.md
  - docs/adr/ADR-009-use-mongodb-for-journals-and-ai-conversations.md
  - docs/adr/ADR-010-use-redis-for-cache-and-rate-limits.md
  - docs/adr/ADR-011-use-qdrant-for-vector-search.md
  - docs/adr/ADR-012-use-webrtc-and-hls-for-video.md
  - docs/adr/ADR-013-use-web3j-and-besu-for-blockchain.md
  - docs/adr/ADR-014-use-javafx-for-desktop.md
  - docs/adr/ADR-015-use-flutter-for-mobile.md
  - docs/adr/ADR-016-use-event-driven-architecture.md
  - docs/adr/ADR-017-use-outbox-pattern.md
  - docs/adr/ADR-018-use-opentelemetry-for-observability.md
---

# LifeOS Engineering Platform - Epic Breakdown

## Overview

This document provides the epic breakdown for LifeOS Engineering Platform, decomposing the requirements from `REQUIREMENTS.md` (acting as the PRD-equivalent — see CONTRIBUTING.md for why it's gitignored) and ADR-001 through ADR-018 into 18 epics with full FR coverage. **Story-level detail (`Story N.M` entries with acceptance criteria, and explicit mappings for NFR1–NFR42 and the 19 Additional Requirements) has not been written yet** — that's Step 3 of the `bmad-create-epics-and-stories` workflow, still pending. Don't read this document as claiming story-level completeness; only the epic layer is done. Requirements Inventory: 91 FRs, 42 NFRs, 19 Additional Requirements (amended after the implementation-readiness review in `docs/implementation-readiness-report-2026-07-31.md` added Engineering Labs and Interview Documentation scope, and expanded the CI/CD NFR into its 14 individually named stages).

**Status note:** Phase 1 of the roadmap is already partially built — `identity-service` (account registration only, no auth yet) and `task-goal-service` (goal create/list + a stateless topological-sort dependency-order computation — no `Task` entity, no goal update/delete, no persisted dependency relationships) exist and are running against real PostgreSQL. Only the specific capabilities that are actually implemented are marked **[DONE]** below (with **[PARTIAL]** for capabilities that are only partly built) rather than marking a whole FR done because a related one is.

## Requirements Inventory

### Functional Requirements

#### API Gateway

- FR1: Route external requests to the appropriate backend service (REST proxying)
- FR2: Enforce authentication on gated routes at the gateway boundary
- FR3: Apply rate limiting per user/client at the gateway
- FR4: Attach a request correlation ID to every inbound request
- FR5: Serve as the GraphQL entry point for aggregated client queries

#### Identity Service

- FR6: Allow a user to register an account [DONE — no auth yet, registration only]
- FR7: Allow a user to log in
- FR8: Support OAuth2/OIDC login
- FR9: Support passkey/WebAuthn login
- FR10: Issue JWTs for authenticated sessions
- FR11: Enforce RBAC/ABAC authorization decisions
- FR12: Allow a user to view and revoke their active devices/sessions

#### Profile Service

- FR13: Allow a user to maintain a personal profile
- FR14: Allow a user to set preferences
- FR15: Allow a user to manage household/family members
- FR16: Allow a user to configure privacy settings
- FR17: Allow a user to configure AI personalization settings

#### Task and Goal Service

- FR18: Allow a user to create, update, and complete tasks [NOT DONE — no `Task` entity, controller, or service exists anywhere in the codebase; only `Goal` does]
- FR19: Allow a user to define goals [PARTIAL — `POST /api/v1/goals` (create) and `GET /api/v1/goals` (list) exist; no update or delete]
- FR20: Allow a user to track habits
- FR21: Allow a user to define routines
- FR22: Allow a user to express dependencies between tasks/goals [PARTIAL — `POST /api/v1/goals/dependency-order` computes a topological order from free-text labels and edges submitted in the request body, but nothing is persisted: there's no foreign key to a stored `Goal` and no migration/table for dependencies, so a user cannot save a dependency against their real goals, only submit a one-off calculation]
- FR23: Allow a user to define milestones
- FR24: Support recurring activities
- FR25: Compute a valid dependency-respecting execution order for goals [DONE — `TopologicalSortService` correctly implements Kahn's algorithm on whatever goals/edges it's given; this FR is about the algorithm's correctness, not persistence, so it's accurately DONE independent of the FR22 gap above]

#### Calendar Service

- FR26: Allow a user to create and manage calendar events
- FR27: Support time blocking
- FR28: Send reminders for upcoming events
- FR29: Detect schedule conflicts
- FR30: Suggest calendar optimizations

#### Finance Service

- FR31: Allow a user to create and manage budgets
- FR32: Allow a user to record transactions
- FR33: Allow a user to categorize transactions
- FR34: Surface spending insights
- FR35: Forecast future spending/income
- FR36: Track progress toward financial goals

#### Document Vault Service

- FR37: Allow a user to upload files
- FR38: Store document metadata
- FR39: Store files via a secure storage reference rather than embedding them in the database
- FR40: Allow a user to search documents
- FR41: Generate an AI summary of a document
- FR42: Request a blockchain proof-of-existence for a document

#### Media Streaming Service

- FR43: Allow a user to schedule a video coaching/journaling session
- FR44: Allow a user to join a live WebRTC room for a session
- FR45: Display a session timer with an end-of-session warning
- FR46: Automatically end a session when required
- FR47: Record sessions
- FR48: Convert recordings to HLS for on-demand playback
- FR49: Transcribe session audio
- FR50: Generate an AI summary of a session
- FR51: Extract action items from a session and create follow-up tasks
- FR52: Optionally anchor a session summary hash to the blockchain

#### AI Orchestrator Service

- FR53: Provide an AI life-assistant interaction surface
- FR54: Answer questions grounded in the user's own documents (RAG)
- FR55: Generate goal-planning recommendations
- FR56: Generate financial insights
- FR57: Summarize sessions/journals
- FR58: Support AI tool-calling to take actions on the user's behalf
- FR59: Log every AI decision for auditability (prompt template id, retrieved context ids, model provider/name, output summary, confidence score, safety flags)

#### Algorithm Engine Service

- FR60: Provide reusable planning/optimization/ranking algorithm implementations as a shared internal capability
- FR61: Support benchmarking of algorithm implementations
- FR62: Provide interview-practice-style examples backed by real product algorithms

#### Blockchain Trust Ledger Service

- FR63: Generate a hash-based proof of existence for a document
- FR64: Generate Merkle proofs across a batch of document hashes
- FR65: Anchor a Merkle root on the blockchain
- FR66: Verify a credential against a previously anchored proof
- FR67: Anchor AI audit hashes on the blockchain
- FR68: Issue goal-achievement certificate proofs

#### Notification Service

- FR69: Send email notifications
- FR70: Send push notifications
- FR71: Deliver real-time notifications via WebSocket/SSE
- FR72: Fan out reminders to the appropriate channel(s)
- FR73: Retry failed notification deliveries
- FR74: Route permanently-failed notifications to a dead-letter path

#### Analytics Service

- FR75: Display dashboard metrics
- FR76: Surface habit trends
- FR77: Surface finance trends
- FR78: Surface productivity insights
- FR79: Generate AI-based recommendations from analytics data
- FR80: Process events into analytics in near-real-time

#### Clients

- FR81: Provide a web dashboard (Angular)
- FR82: Provide a desktop client (JavaFX)
- FR83: Provide iOS and Android clients (Flutter)

#### Engineering Labs

*Added during implementation-readiness review — missing from the first extraction pass.*

- FR84: Provide an Algorithms Lab covering arrays, strings, hash maps, linked lists, trees, graphs, heaps, tries, dynamic programming, backtracking, greedy algorithms, Union-Find, segment trees, Fenwick trees, and Bloom filters, each connected to a real product use case with documented complexity
- FR85: Provide a Concurrency Lab comparing platform threads, virtual threads, `ExecutorService`, `CompletableFuture`, structured concurrency, and scoped values, with cancellation/timeout/thread-dump/JFR examples and load-test comparisons
- FR86: Provide a Distributed Systems Lab demonstrating service discovery, distributed tracing, circuit breakers, retries, backpressure, idempotency, saga pattern, outbox pattern, CQRS, event sourcing, distributed locks, leader election, sharding, and consistent hashing
- FR87: Provide a Performance Lab with k6 load tests, JVM tuning, GC comparisons, JFR profiling, query plan analysis, cache hit ratio tests, REST vs. gRPC benchmarks, GraphQL aggregation benchmarks, and virtual-threads benchmarks
- FR88: Provide a Blockchain Lab demonstrating Merkle tree implementation, document hash proofs, smart contract integration, a Besu local network, the Web3j client, transaction indexing, Bloom filter transaction lookup, credential proof verification, and a consensus simulator
- FR89: Provide an AI Lab demonstrating prompt templates, a RAG pipeline, embedding generation, vector search, AI tool calling, AI output evaluation, AI audit logging, and local + cloud model provider abstraction
- FR90: Provide a System Design Lab implementing 10 named mini-systems — URL shortener, notification system, search engine, distributed scheduler, recommendation engine, rate limiter, chat/messaging system, video session system, document storage system, event analytics pipeline — each documented with requirements, APIs, data model, scaling strategy, bottlenecks, tradeoffs, failure handling, and monitoring

#### Interview Documentation

*Added during implementation-readiness review — missing from the first extraction pass.*

- FR91: Maintain interview documentation under `docs/interview/` answering, for each major technology choice: why it was chosen, what alternatives were considered, its tradeoffs, when it would be the wrong choice, its scaling bottlenecks, how it fails, how it's monitored, and how it could be improved [DONE — 19 documents exist]

### NonFunctional Requirements

#### Reliability

- NFR1: Services must implement circuit breakers for calls to failure-prone dependencies
- NFR2: Services must retry transient failures with exponential backoff
- NFR3: All outbound/inbound calls must have explicit timeouts
- NFR4: Services must implement bulkhead isolation to contain failure blast radius
- NFR5: Write operations that can be retried must be idempotent (idempotency keys)
- NFR6: Failed asynchronous work must go to a dead-letter path rather than being silently dropped
- NFR7: Domain events must be published via the transactional outbox pattern, never a dual write
- NFR8: Multi-service workflows requiring compensation must use saga orchestration
- NFR9: Public-facing endpoints must be rate limited
- NFR10: Services must degrade gracefully rather than fail outright when a non-critical dependency is unavailable
- NFR11: High-throughput consumers must implement backpressure
- NFR12: Every service must expose health, readiness, and liveness checks

#### Observability

- NFR13: Every service must emit distributed traces via OpenTelemetry
- NFR14: Every service must emit metrics scraped by Prometheus
- NFR15: Every service must ship structured logs to Loki
- NFR16: Request latency, error rate, dependency latency, DB query latency, cache hit ratio, event-processing lag, AI request latency, video processing time, blockchain confirmation time, JVM memory/GC, and virtual-thread metrics must be tracked per relevant service

#### Security

- NFR17: Authentication must support OAuth2/OIDC
- NFR18: Authentication must support passkeys/WebAuthn
- NFR19: Authorization must support both RBAC and ABAC models
- NFR20: Service-to-service calls must be authenticated, with mTLS where appropriate
- NFR21: Secrets must be managed via a secrets manager, never hardcoded
- NFR22: Data must be encrypted at rest
- NFR23: Data must be encrypted in transit
- NFR24: File uploads must be validated for safety before storage
- NFR25: Security-relevant actions must be audit logged
- NFR26: All user input must be validated against OWASP guidance

#### Testing & Delivery

- NFR27: Test coverage must include unit, integration, contract, end-to-end, performance, mutation, security, architecture, and chaos tests
- NFR28: Performance-sensitive changes must be benchmarked with a documented methodology (no invented numbers — see `docs/benchmarks/`)

#### CI/CD Pipeline

*Expanded during implementation-readiness review — REQUIREMENTS.md names 14 individual stages, originally collapsed into one NFR.*

- NFR29: CI must compile the project on every change [DONE — `ci.yml`]
- NFR30: CI must run a format check on every change
- NFR31: CI must run unit tests on every change [DONE — `ci.yml`, part of `./gradlew build`]
- NFR32: CI must run integration tests on every change [DONE — `ci.yml`, part of `./gradlew build`]
- NFR33: CI must run contract tests on every change
- NFR34: CI must run static analysis on every change
- NFR35: CI must run a security scan on every change
- NFR36: CI must run mutation testing on every change
- NFR37: CI must build a Docker image on every change
- NFR38: CI must generate an SBOM (software bill of materials) on every change
- NFR39: CI must run a container scan on every change
- NFR40: CI must deploy to staging on every change
- NFR41: CI must run smoke tests against staging on every change
- NFR42: CI must publish test reports on every change

### Additional Requirements

- Use Java 25 as the default language across backend services, the algorithm engine, AI orchestration, blockchain integration, CLI tooling, and the JavaFX desktop client (ADR-001)
- Default every service's request-handling threads to virtual threads (`spring.threads.virtual.enabled=true`) (ADR-002) [DONE — both existing services]
- Use `StructuredTaskScope` for grouped concurrent fan-out/fan-in workflows, paired with virtual threads (ADR-003)
- Use `ScopedValue` for request-scoped context propagation (user, tenant, correlation ID, AI session), bound at the ingress boundary (ADR-004)
- Build each service as an independently deployable Spring Boot microservice, each owning its own responsibility and datastore where warranted (ADR-005) [DONE — pattern established by the two existing services]
- Provide a GraphQL gateway for dashboard/aggregated client views, resolving internally over gRPC (ADR-006)
- Use gRPC with versioned `.proto` contracts in a shared `grpc-contracts` module for all internal service-to-service calls (ADR-007)
- Use PostgreSQL as the system of record for identity, task/goal, calendar, finance, and audit/permission domains, one schema/database per owning service (ADR-008) [DONE — both existing services]
- Use MongoDB for journals, notes, and AI conversation history, owned only by the profile/journal and AI orchestrator services (ADR-009)
- Use Redis as the shared cache/session/rate-limit/lock/pub-sub layer across all services (ADR-010) [Infra provisioned via docker-compose, not yet used by any service]
- Use Qdrant as the dedicated vector database for embeddings/RAG/semantic search (ADR-011)
- Use WebRTC (SFU architecture) for live sessions and transcode recordings to HLS via ffmpeg for playback (ADR-012)
- Run a private Hyperledger Besu network with Web3j as the Java client; anchor only Merkle roots and minimal metadata on-chain, never private data (ADR-013)
- Build the desktop client with JavaFX on Java 25, AOT-compiled via GraalVM Native Image (ADR-014)
- Build iOS and Android clients with Flutter, sharing REST/GraphQL contracts with other clients (ADR-015)
- Use Kafka as the default event backbone, with Pulsar as an acceptable substitute (ADR-016)
- Implement the transactional outbox pattern for every service publishing domain events, with a relay process and idempotency-keyed delivery (ADR-017)
- Instrument every service with OpenTelemetry (traces/metrics/log correlation), backed by Prometheus/Grafana/Loki/Tempo (ADR-018)
- No third-party starter template is used — the Gradle multi-module monorepo (`settings.gradle.kts` + root `build.gradle.kts`, Java 25 toolchain via the foojay-resolver-convention plugin) is itself the starter scaffold, already built [DONE]

### UX Design Requirements

None — no UX design document exists yet, and no client UI (web/desktop/mobile) has been built. REQUIREMENTS.md names Angular (web), JavaFX (desktop), and Flutter (mobile) as the target clients, but no visual identity, interaction patterns, or mockups have been defined. UX design work should precede or run alongside the client epics (Epic 14–16, see below) — this is called out again in each of those epics' Implementation Notes.

### FR Coverage Map

- FR1: Epic 2 - API Gateway routing
- FR2: Epic 2 - API Gateway auth enforcement
- FR3: Epic 2 - API Gateway rate limiting
- FR4: Epic 2 - API Gateway correlation IDs
- FR5: Epic 13 - GraphQL dashboard aggregation entry point
- FR6: Epic 1 - Account registration [DONE]
- FR7: Epic 1 - Login
- FR8: Epic 1 - OAuth2/OIDC login
- FR9: Epic 1 - Passkey/WebAuthn login
- FR10: Epic 1 - JWT issuance
- FR11: Epic 1 - RBAC/ABAC authorization
- FR12: Epic 1 - Device/session management
- FR13: Epic 4 - Personal profile
- FR14: Epic 4 - Preferences
- FR15: Epic 4 - Household/family members
- FR16: Epic 4 - Privacy settings
- FR17: Epic 4 - AI personalization settings
- FR18: Epic 5 - Task CRUD [NOT DONE]
- FR19: Epic 5 - Goal definition [PARTIAL — create + list only]
- FR20: Epic 5 - Habit tracking
- FR21: Epic 5 - Routines
- FR22: Epic 5 - Task/goal dependencies [PARTIAL — computed, not persisted]
- FR23: Epic 5 - Milestones
- FR24: Epic 5 - Recurring activities
- FR25: Epic 5 - Dependency-ordered execution (topological sort) [DONE]
- FR26: Epic 6 - Calendar events
- FR27: Epic 6 - Time blocking
- FR28: Epic 6 - Event reminders (consumes Epic 3)
- FR29: Epic 6 - Schedule conflict detection
- FR30: Epic 6 - Calendar optimization suggestions
- FR31: Epic 7 - Budget management
- FR32: Epic 7 - Transaction recording
- FR33: Epic 7 - Transaction categorization
- FR34: Epic 7 - Spending insights
- FR35: Epic 7 - Spending/income forecasting
- FR36: Epic 7 - Financial goal tracking
- FR37: Epic 11 - Document upload
- FR38: Epic 11 - Document metadata
- FR39: Epic 11 - Secure storage reference
- FR40: Epic 11 - Document search
- FR41: Epic 11 - AI document summary (consumes Epic 10)
- FR42: Epic 11 - Blockchain proof request (consumes Epic 8)
- FR43: Epic 12 - Session scheduling
- FR44: Epic 12 - Join WebRTC room
- FR45: Epic 12 - Session timer + warning
- FR46: Epic 12 - Auto-end session
- FR47: Epic 12 - Session recording
- FR48: Epic 12 - HLS conversion
- FR49: Epic 12 - Audio transcription
- FR50: Epic 12 - AI session summary (consumes Epic 10)
- FR51: Epic 12 - Action items to follow-up tasks (consumes Epic 5)
- FR52: Epic 12 - Optional blockchain anchor (consumes Epic 8)
- FR53: Epic 10 - AI life-assistant interaction surface
- FR54: Epic 11 - RAG over documents (consumes Epic 10)
- FR55: Epic 10 - Goal-planning recommendations (consumes Epic 5)
- FR56: Epic 10 - Financial insights (consumes Epic 7)
- FR57: Epic 10 - Session/journal summaries
- FR58: Epic 10 - AI tool-calling
- FR59: Epic 10 - AI decision audit logging
- FR60: Epic 9 - Reusable algorithm implementations
- FR61: Epic 9 - Algorithm benchmarking
- FR62: Epic 9 - Interview-practice examples
- FR63: Epic 8 - Document hash proof-of-existence
- FR64: Epic 8 - Merkle proof generation
- FR65: Epic 8 - Merkle root anchoring
- FR66: Epic 8 - Credential proof verification
- FR67: Epic 8 - AI audit hash anchoring
- FR68: Epic 8 - Goal-achievement certificate proofs
- FR69: Epic 3 - Email notifications
- FR70: Epic 3 - Push notifications
- FR71: Epic 3 - WebSocket/SSE notifications
- FR72: Epic 3 - Reminder fanout
- FR73: Epic 3 - Retry failed deliveries
- FR74: Epic 3 - Dead-letter routing
- FR75: Epic 13 - Dashboard metrics (consumes Epics 5, 6, 7)
- FR76: Epic 13 - Habit trends
- FR77: Epic 13 - Finance trends
- FR78: Epic 13 - Productivity insights
- FR79: Epic 13 - AI-generated recommendations (consumes Epic 10)
- FR80: Epic 13 - Near-real-time event processing
- FR81: Epic 14 - Web dashboard (Angular)
- FR82: Epic 15 - Desktop client (JavaFX)
- FR83: Epic 16 - Mobile clients (Flutter)
- FR84: Epic 17 - Algorithms Lab
- FR85: Epic 17 - Concurrency Lab
- FR86: Epic 17 - Distributed Systems Lab
- FR87: Epic 17 - Performance Lab
- FR88: Epic 17 - Blockchain Lab
- FR89: Epic 17 - AI Lab
- FR90: Epic 17 - System Design Lab
- FR91: Epic 18 - Interview documentation [DONE]

All 91 FRs are covered by exactly one epic. NFR1–NFR42 and the 19 Additional Requirements are cross-cutting — each is addressed within the acceptance criteria of whichever stories first need them (e.g., the outbox pattern's NFR7 lands in whichever epic's story first publishes a domain event; OpenTelemetry's NFR13 lands in Epic 1's first story, since every service should be instrumented from its first endpoint) rather than owned by a single dedicated epic, per standard practice for non-functional requirements.

## Epic List

### Epic 1: Account Identity & Access

Users can register, log in (including via OAuth2/OIDC and passkeys), and manage their own sessions and authorization — the foundation every other epic builds on.

- **FRs covered:** FR6, FR7, FR8, FR9, FR10, FR11, FR12
- **Status:** Partially done — registration (FR6) exists in `identity-service`; login, OAuth2/OIDC, passkeys, JWT, RBAC/ABAC, and session management are not yet built.
- **Implementation notes:** First place to wire up OpenTelemetry (NFR13–16), scoped-value context binding (Additional Requirements, ADR-004), and rate limiting (NFR9) — every later epic inherits these patterns from here.

### Epic 2: Unified Platform Gateway

Users interact with LifeOS through one coherent, reliable entry point rather than hitting fragile individual services directly — requests are authenticated, rate-limited, and traceable end-to-end.

- **FRs covered:** FR1, FR2, FR3, FR4
- **Implementation notes:** Depends on Epic 1 for the auth decisions it enforces. Covers FR4 (correlation IDs), NFR9 (rate limiting), and NFR13 (OpenTelemetry distributed tracing) at the edge.

### Epic 3: Reminders & Notifications

Users receive timely email, push, and real-time notifications, with reliable delivery even when a channel is temporarily unavailable.

- **FRs covered:** FR69, FR70, FR71, FR72, FR73, FR74
- **Implementation notes:** Depends on Epic 1. First natural home for the outbox pattern (NFR7), retry/backoff (NFR2), and dead-letter handling (NFR6) — later epics (Calendar, Video) call into this one rather than reimplementing delivery.

### Epic 4: Personal Profile & Preferences

Users maintain a personal profile, preferences, household members, privacy settings, and AI personalization settings.

- **FRs covered:** FR13, FR14, FR15, FR16, FR17
- **Implementation notes:** Depends on Epic 1. Straightforward CRUD domain — first candidate for MongoDB usage (ADR-009) since preferences/household data is semi-structured.

### Epic 5: Task & Goal Management

Users create tasks and goals, track habits and routines, express dependencies between them, and see a valid execution order.

- **FRs covered:** FR18, FR19, FR20, FR21, FR22, FR23, FR24, FR25
- **Status:** Partially done — goal create/list (FR19) and dependency-order computation (FR25, the algorithm itself is correct and complete) exist in `task-goal-service`. Not actually done despite earlier drafts of this doc claiming otherwise: there is no `Task` entity at all (FR18), goals have no update/delete (FR19 is create+list only), and dependency data isn't persisted against real goals (FR22 computes an order from submitted data but doesn't store a dependency relationship). Habits, routines, milestones, and recurrence (FR20, FR21, FR23, FR24) are not yet built either.
- **Implementation notes:** Depends on Epic 1. The existing dependency-ordering implementation reimplements Kahn's algorithm directly rather than calling a shared Algorithm Engine — note this as a future consolidation opportunity once Epic 9 exists, not a blocker.

### Epic 6: Calendar & Scheduling

Users manage calendar events, block time, get reminded before events, and get conflict/optimization help.

- **FRs covered:** FR26, FR27, FR28, FR29, FR30
- **Implementation notes:** Depends on Epic 1 and Epic 3 (FR28 reminders call the Notification epic rather than reimplementing delivery).

### Epic 7: Personal Finance & Budgeting

Users manage budgets, record and categorize transactions, and get spending insights and forecasts.

- **FRs covered:** FR31, FR32, FR33, FR34, FR35, FR36
- **Implementation notes:** Depends on Epic 1. PostgreSQL system-of-record domain (ADR-008) — financial correctness (idempotent posting, NFR5) matters most here.

### Epic 8: Blockchain Trust & Verification

Users can get tamper-evident proof that a document, credential, or achievement is genuine and unaltered, without exposing private data on-chain.

- **FRs covered:** FR63, FR64, FR65, FR66, FR67, FR68
- **Implementation notes:** Depends on Epic 1. Standalone utility other epics (Document Vault, Video) call into for proof requests — built once, consumed repeatedly.

### Epic 9: Algorithm Engine & Interview Readiness

As an engineer using this project for FAANG-style interview preparation, reusable, benchmarked algorithm implementations exist that power real product features and double as interview-practice material — a secondary persona this project explicitly serves (see REQUIREMENTS.md "Career Goals This Project Supports").

- **FRs covered:** FR60, FR61, FR62
- **Implementation notes:** No hard dependency on other epics; can be built anytime, but delivers most value once at least one domain epic (Task/Goal, Calendar, Finance) exists to point its algorithms at as "real product use cases" rather than isolated examples.

### Epic 10: AI Life Assistant

Users get an AI assistant that gives goal-planning recommendations, financial insights, and session summaries, with every AI decision logged for auditability.

- **FRs covered:** FR53, FR55, FR56, FR57, FR58, FR59
- **Implementation notes:** Depends on Epic 1, Epic 5 (FR55 needs goal data), Epic 7 (FR56 needs finance data). RAG-over-documents (originally FR54) is intentionally NOT in this epic — it's grouped into Epic 11 (Document Vault) instead, since it can't deliver value until documents exist, avoiding a circular dependency between this epic and Document Vault.

### Epic 11: Document Vault

Users upload, search, and get AI summaries of their documents, with tamper-evident proof-of-existence available on request.

- **FRs covered:** FR37, FR38, FR39, FR40, FR41, FR42, FR54
- **Implementation notes:** Depends on Epic 1, Epic 8 (FR42), and Epic 10 (FR41, FR54). Upload/metadata/search (FR37–40) can ship as the epic's first stories without waiting on AI or blockchain; FR41/FR42/FR54 are later stories within this same epic once their dependencies exist.

### Epic 12: Video Coaching & Journaling

Users schedule and join live coaching/journaling video sessions, with recordings, transcription, AI summaries, and automatic follow-up task creation.

- **FRs covered:** FR43, FR44, FR45, FR46, FR47, FR48, FR49, FR50, FR51, FR52
- **Implementation notes:** Depends on Epic 1, Epic 3 (scheduling reminders), Epic 5 (FR51 creates tasks), Epic 10 (FR50 summary), Epic 8 (FR52, optional). The largest single epic by FR count — consider splitting into "live session mechanics" (FR43–46) and "post-session processing" (FR47–52) stories within the epic if a single dev agent's context gets strained.

### Epic 13: Personal Analytics & Insights Dashboard

Users see a unified dashboard of metrics, trends, and AI-generated recommendations drawn from across the whole platform in one aggregated view.

- **FRs covered:** FR5, FR75, FR76, FR77, FR78, FR79, FR80
- **Implementation notes:** Depends on Epic 5, Epic 6, Epic 7 (data sources) and Epic 10 (FR79). First real consumer of the GraphQL aggregation gateway (ADR-006) — FR5 belongs here rather than Epic 2 because a GraphQL aggregation layer has nothing to aggregate until data-producing epics exist.

### Epic 14: Web Dashboard Client

Users access LifeOS through a web dashboard.

- **FRs covered:** FR81
- **Implementation notes:** Requires a UX design pass (visual identity, interaction patterns, mockups) before story-writing — see "UX Design Requirements" above. Depends on whichever backend epics the initial dashboard scope surfaces (at minimum Epic 1, Epic 5, Epic 13).

### Epic 15: Desktop Client

Users access LifeOS through a native desktop application.

- **FRs covered:** FR82
- **Implementation notes:** Same UX-design prerequisite as Epic 14. JavaFX + GraalVM Native Image (ADR-014).

### Epic 16: Mobile Clients

Users access LifeOS through native iOS and Android apps.

- **FRs covered:** FR83
- **Implementation notes:** Same UX-design prerequisite as Epic 14. Flutter (ADR-015), sharing REST/GraphQL contracts with the other clients.

### Epic 17: Engineering Labs

As an engineer using this project for FAANG-style interview preparation, a dedicated playground exists to practice and demonstrate algorithms, concurrency patterns, distributed-systems patterns, performance engineering, blockchain fundamentals, AI engineering, and system design — each lab is a standalone learning/demonstration deliverable, not a dependency of the product epics.

- **FRs covered:** FR84, FR85, FR86, FR87, FR88, FR89, FR90
- **Implementation notes:** No hard dependency on other epics — can run in parallel with product epics at any time — but each lab is most valuable once it can reference a real product use case from an existing epic (e.g., the Blockchain Lab after Epic 8, the AI Lab after Epic 10), so sequencing it late is a deliberate choice, not a requirement.

### Epic 18: Interview & Portfolio Documentation

As an engineer using this project for FAANG-style interview preparation, every major technology choice has a documented why/alternatives/tradeoffs/failure-mode explanation ready to use in an interview.

- **FRs covered:** FR91
- **Status:** Done — 19 documents exist under `docs/interview/`.
- **Implementation notes:** Ongoing/maintenance epic — revisit whenever a new ADR is added (per CLAUDE.md's ADR policy) to keep interview docs in sync with real decisions.
