---
stepsCompleted: [1, 2, 3, 4]
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
  - docs/adr/ADR-019-automate-releases-with-github-actions.md
  - docs/adr/ADR-020-use-identity-service-for-multi-mode-authentication-and-session-management.md
  - docs/architecture/current-state.md
  - docs/api/identity-service.md
  - docs/api/task-goal-service.md
  - docs/PROJECT_MANAGEMENT.md
  - docs/ux-designs/DESIGN.md
  - docs/ux-designs/EXPERIENCE.md
---

# LifeOS Engineering Platform - Epic Breakdown

## Overview

This document provides the epic and story breakdown for LifeOS Engineering Platform, decomposing the requirements from `REQUIREMENTS.md` (acting as the PRD-equivalent — see CONTRIBUTING.md for why it's gitignored), the current architecture, API contracts, and ADR-001 through ADR-020 into 18 epics with full FR coverage. Story-level detail is now included below: each story is sized for one implementation session, names its user value, and carries testable acceptance criteria. Requirements Inventory: 91 FRs, 42 NFRs, 19 Additional Requirements (amended after the implementation-readiness review in `docs/implementation-readiness-report-2026-07-31.md` added Engineering Labs and Interview Documentation scope, and expanded the CI/CD NFR into its 14 individually named stages).

**Status note:** Phase 1 of the roadmap is already partially built — `identity-service` (account registration plus first-party email/password login) and `task-goal-service` (goal create/list + a stateless topological-sort dependency-order computation — no `Task` entity, no goal update/delete, no persisted dependency relationships) exist and are running against real PostgreSQL. Only the specific capabilities that are actually implemented are marked **[DONE]** below (with **[PARTIAL]** for capabilities that are only partly built) rather than marking a whole FR done because a related one is.

## Requirements Inventory

### Functional Requirements

#### API Gateway

- FR1: Route external requests to the appropriate backend service (REST proxying)
- FR2: Enforce authentication on gated routes at the gateway boundary
- FR3: Apply rate limiting per user/client at the gateway
- FR4: Attach a request correlation ID to every inbound request
- FR5: Serve as the GraphQL entry point for aggregated client queries

#### Identity Service

- FR6: Allow a user to register an account [DONE]
- FR7: Allow a user to log in [DONE — first-party email/password flow]
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

- FR91: Maintain interview documentation under `docs/interview/` answering, for each major technology choice: why it was chosen, what alternatives were considered, its tradeoffs, when it would be the wrong choice, its scaling bottlenecks, how it fails, how it's monitored, and how it could be improved [DONE — 20 documents exist]

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
- Use Redis as the shared cache/session/rate-limit/lock/pub-sub layer across all services (ADR-010) [PARTIAL — identity authentication rate limiting and short-lived OIDC/WebAuthn state]
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

The baseline UX contract is in [`docs/ux-designs/DESIGN.md`](ux-designs/DESIGN.md) and [`docs/ux-designs/EXPERIENCE.md`](ux-designs/EXPERIENCE.md). Both are currently `draft`: visual and interaction assumptions must be confirmed before client implementation is unblocked. The BMAD working run remains under the ignored `_bmad-output/planning-artifacts/ux-designs/` directory.

- UX-DR1: Use one shared information architecture and terminology across Angular web, JavaFX desktop, and Flutter mobile while preserving native navigation and secure-storage conventions.
- UX-DR2: Define accessible authentication flows for password, OAuth2/OIDC, and passkeys with generic errors, safe recovery, and no client-side secret leakage.
- UX-DR3: Define Home/Plan/Calendar/Money/Vault/Assistant/Sessions/Settings surfaces with clear ownership, freshness, partial-data, stale-data, and unauthorized states.
- UX-DR4: Make privacy, consent, AI uncertainty, confirmation, and destructive/revocation behavior visible and recoverable.
- UX-DR5: Meet WCAG 2.2 AA and equivalent platform guidance through keyboard/switch access, screen-reader announcements, visible focus, dynamic type, captions, and non-color state encoding.
- UX-DR6: Define responsive behavior for web breakpoints, resizable desktop panes, and mobile touch/back/permission/notification behavior.
- UX-DR7: Use calm, precise microcopy that explains impact and recovery without gamification, artificial urgency, or private data in notification previews.

### FR Coverage Map

- FR1: Epic 2 - API Gateway routing
- FR2: Epic 2 - API Gateway auth enforcement
- FR3: Epic 2 - API Gateway rate limiting
- FR4: Epic 2 - API Gateway correlation IDs
- FR5: Epic 13 - GraphQL dashboard aggregation entry point
- FR6: Epic 1 - Account registration [DONE]
- FR7: Epic 1 - Login [DONE — first-party email/password]
- FR8: Epic 1 - OAuth2/OIDC login
- FR9: Epic 1 - Passkey/WebAuthn login
- FR10: Epic 1 - JWT issuance [PARTIAL — first-party access tokens; refresh/JWKS remain]
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

### Story Coverage Map

| Requirements | Owning stories |
| --- | --- |
| FR1, FR4 | 2.1 |
| FR2 | 2.2 |
| FR3 | 2.3 |
| FR6 | 1.1 |
| FR7 | 1.2 |
| FR8 | 1.3 |
| FR9 | 1.4 |
| FR10 | 1.5 |
| FR11 | 1.6 |
| FR12 | 1.7 |
| FR13–FR17 | 4.1–4.5 |
| FR18 | 5.1 |
| FR19 | 5.2 |
| FR20 | 5.3 |
| FR21, FR24 | 5.4 |
| FR22 | 5.5 |
| FR23 | 5.7 |
| FR25 | 5.6 |
| FR26–FR30 | 6.1–6.5 |
| FR31–FR36 | 7.1–7.6 |
| FR37–FR42, FR54 | 11.1–11.7 |
| FR43–FR52 | 12.1–12.10 |
| FR53 | 10.1 |
| FR55 | 10.2 |
| FR56 | 10.3 |
| FR57 | 10.4 |
| FR58 | 10.5 |
| FR59 | 10.6 |
| FR60–FR62 | 9.1–9.3 |
| FR63–FR68 | 8.1–8.6 |
| FR69 | 3.1 |
| FR70 | 3.2 |
| FR71 | 3.3 |
| FR72 | 3.4 |
| FR73–FR74 | 3.5 |
| FR5, FR75 | 13.1 |
| FR76–FR80 | 13.2–13.6 |
| FR81 | 14.1–14.5 |
| FR82 | 15.1–15.4 |
| FR83 | 16.1–16.4 |
| FR84–FR90 | 17.1–17.11 |
| FR91 | 18.1 |

#### Per-number FR ownership

- FR1 → 2.1
- FR2 → 2.2
- FR3 → 2.3
- FR4 → 2.1
- FR5 → 13.1
- FR6 → 1.1
- FR7 → 1.2
- FR8 → 1.3
- FR9 → 1.4
- FR10 → 1.5
- FR11 → 1.6
- FR12 → 1.7
- FR13 → 4.1
- FR14 → 4.2
- FR15 → 4.3
- FR16 → 4.4
- FR17 → 4.5
- FR18 → 5.1
- FR19 → 5.2
- FR20 → 5.3
- FR21 → 5.4
- FR22 → 5.5
- FR23 → 5.7
- FR24 → 5.4
- FR25 → 5.6
- FR26 → 6.1
- FR27 → 6.2
- FR28 → 6.3
- FR29 → 6.4
- FR30 → 6.5
- FR31 → 7.1
- FR32 → 7.2
- FR33 → 7.3
- FR34 → 7.4
- FR35 → 7.5
- FR36 → 7.6
- FR37 → 11.1
- FR38 → 11.2
- FR39 → 11.3
- FR40 → 11.4
- FR41 → 11.5
- FR42 → 11.6
- FR43 → 12.1
- FR44 → 12.2
- FR45 → 12.3
- FR46 → 12.4
- FR47 → 12.5
- FR48 → 12.6
- FR49 → 12.7
- FR50 → 12.8
- FR51 → 12.9
- FR52 → 12.10
- FR53 → 10.1
- FR54 → 11.7
- FR55 → 10.2
- FR56 → 10.3
- FR57 → 10.4
- FR58 → 10.5
- FR59 → 10.6
- FR60 → 9.1
- FR61 → 9.2
- FR62 → 9.3
- FR63 → 8.1
- FR64 → 8.2
- FR65 → 8.3
- FR66 → 8.4
- FR67 → 8.5
- FR68 → 8.6
- FR69 → 3.1
- FR70 → 3.2
- FR71 → 3.3
- FR72 → 3.4
- FR73 → 3.5
- FR74 → 3.5
- FR75 → 13.1
- FR76 → 13.2
- FR77 → 13.3
- FR78 → 13.4
- FR79 → 13.5
- FR80 → 13.6
- FR81 → 14.1–14.5
- FR82 → 15.1–15.4
- FR83 → 16.1–16.4
- FR84 → 17.1
- FR85 → 17.2
- FR86 → 17.3
- FR87 → 17.4
- FR88 → 17.5
- FR89 → 17.6
- FR90 → 17.7–17.11
- FR91 → 18.1

### NFR and Additional Requirement Story Map

| Requirement group | Owning stories / enforcement point |
| --- | --- |
| NFR1–NFR4: dependency resilience, timeouts, bulkheads | 2.3, 3.5, 6.3, 7.4–7.5, 8.3, 10.1, 11.5, 12.4–12.8, 13.1, 13.6; required in every external dependency story |
| NFR5–NFR8: idempotency, dead letters, outbox, saga | 1.5–1.7, 3.1, 3.4–3.5, 6.3, 8.3, 10.5–10.6, 11.6, 12.4, 12.6, 12.9–12.10, 13.6 |
| NFR9–NFR12: rate limits, graceful degradation, backpressure, health checks | 1.1–1.2, 1.7, 2.2–2.3, 3.3–3.5, 6.3, 10.1, 11.4, 12.2, 13.1, 13.6; health/readiness/liveness is a definition-of-done check for every service story |
| NFR13–NFR16: traces, metrics, logs, operational signals | 1.1, 2.1–2.3, 3.1–3.5, 4.1, 5.1, 6.1, 7.1, 8.1, 10.1, 11.1, 12.1, 13.1, 13.6, 18.2 |
| NFR17–NFR20: OAuth/OIDC, WebAuthn, RBAC/ABAC, service identity | 1.3–1.6, 2.2, 8.4, 10.5, 14.2, 15.2, 16.2 |
| NFR21–NFR26: secrets, encryption, upload safety, audit, OWASP validation | 1.1–1.7, 2.2, 4.1–4.5, 5.1–5.8, 7.1–7.6, 8.1–8.6, 10.1–10.6, 11.1–11.7, 12.1–12.10, 14.2–14.4, 15.2, 16.2–16.4 |
| NFR27: full test portfolio | Every implementation story; CI/test-report ownership in 18.2 |
| NFR28: documented benchmarks | 5.6, 9.2–9.3, 17.2, 17.4, 18.2 |
| NFR29–NFR42: CI/CD quality pipeline | 18.2, with implementation status tracked per named pipeline stage |
| Additional 1–5: Java 25, virtual threads, structured concurrency, scoped values, service boundaries | 1.1, 2.1, 3.1, 5.1, 6.1, 7.1, 8.1, 9.1, 10.1, 11.1, 12.1, 13.1, 17.1–17.4 |
| Additional 6–7: GraphQL aggregation and gRPC contracts | 2.1, 6.5, 7.4–7.5, 10.2–10.5, 11.7, 12.9, 13.1, 14.3 |
| Additional 8–10: PostgreSQL, MongoDB, Redis | 1.1, 1.2, 1.7, 2.3, 4.1–4.5, 5.1–5.8, 6.1, 7.1–7.6, 10.4, 11.2–11.7 |
| Additional 11–13: Qdrant, WebRTC/HLS, Besu/Web3j | 8.1–8.6, 10.1–10.4, 11.4–11.7, 12.2, 12.5–12.10, 17.5–17.6 |
| Additional 14–15: JavaFX and Flutter clients | 15.1–15.4 and 16.1–16.4 |
| Additional 16–18: Kafka/Pulsar, outbox, OpenTelemetry | 3.1–3.5, 6.3, 8.3, 10.6, 12.4, 12.6, 12.9, 13.6, 18.2 |
| Additional 19: existing Gradle/Java 25 scaffold | 1.1 and 18.2; no duplicate scaffold story is required |
| UX-DR1–UX-DR7 | 14.1–14.4, 15.1–15.3, 16.1–16.4; client implementation remains gated on UX contract approval |

#### Per-number NFR ownership

- NFR1 → 2.3, 3.5, 6.3, 7.4, 7.5, 8.3, 10.1, 11.5, 12.4, 12.6, 13.1, 13.6
- NFR2 → 3.5, 5.8, 6.3, 11.5, 12.6, 12.7, 12.8, 13.6, 16.4
- NFR3 → 1.2, 2.1, 2.3, 3.1, 5.1, 6.1, 7.1, 8.1, 10.1, 11.1, 12.1, 13.1
- NFR4 → 2.3, 3.5, 10.1, 12.6
- NFR5 → 1.5, 1.7, 3.1, 3.4, 5.1, 7.2, 8.3, 11.1, 12.4, 12.9, 13.6, 16.4
- NFR6 → 3.5, 12.6, 13.6, 18.2
- NFR7 → 3.1, 3.4, 8.3, 10.6, 11.6, 12.4, 12.9, 13.6
- NFR8 → 3.5, 8.3, 12.9, 12.10, 13.6
- NFR9 → 1.2, 2.3, 3.3, 3.5, 12.2, 16.4
- NFR10 → 2.3, 3.5, 6.3, 10.1, 11.4, 12.2, 13.1, 13.6
- NFR11 → 3.3, 3.5, 8.2, 12.6, 13.6
- NFR12 → 1.1, 2.1, 3.1, 5.1, 6.1, 7.1, 8.1, 10.1, 11.1, 12.1, 13.1
- NFR13 → 1.1, 2.1, 3.1, 5.1, 6.1, 7.1, 8.1, 10.1, 11.1, 12.1, 13.1, 13.6
- NFR14 → 1.1, 2.1, 3.1, 5.1, 6.1, 7.1, 8.1, 10.1, 11.1, 12.1, 13.1, 13.6
- NFR15 → 1.1, 2.1, 3.1, 5.1, 6.1, 7.1, 8.1, 10.1, 11.1, 12.1, 13.1, 13.6
- NFR16 → 1.1, 2.1, 3.1, 5.1, 6.1, 7.1, 8.1, 10.1, 11.1, 12.1, 13.1, 13.6
- NFR17 → 1.3, 14.2, 15.2, 16.2
- NFR18 → 1.4, 16.2
- NFR19 → 1.6, 4.3, 5.1, 7.1, 10.5, 11.2, 12.2, 13.1
- NFR20 → 1.5, 1.6, 2.2, 8.3, 12.2, 13.1, 15.2
- NFR21 → 1.2, 1.3, 1.4, 11.1, 14.2, 15.2, 16.2
- NFR22 → 1.5, 2.2, 11.3, 12.5
- NFR23 → 1.5, 2.2, 11.3, 12.2, 14.1, 15.1, 16.1
- NFR24 → 11.1, 11.3
- NFR25 → 1.2, 1.6, 1.7, 8.5, 10.6, 14.2
- NFR26 → 1.2, 2.2, 5.1, 11.1, 14.4, 16.3
- NFR27 → 1.1, 5.1, 9.1, 9.2, 11.1, 14.4, 18.2
- NFR28 → 5.6, 9.2, 9.3, 17.2, 17.4, 18.2
- NFR29 → 18.2
- NFR30 → 18.2
- NFR31 → 18.2
- NFR32 → 18.2
- NFR33 → 18.2
- NFR34 → 18.2
- NFR35 → 18.2
- NFR36 → 18.2
- NFR37 → 18.2
- NFR38 → 18.2
- NFR39 → 18.2
- NFR40 → 18.2
- NFR41 → 18.2
- NFR42 → 18.2

#### Per-number Additional Requirement ownership

- Additional 1 (Java 25) → 1.1, 5.1, 9.1, 15.1, 17.1–17.4
- Additional 2 (virtual threads) → 1.1, 2.3, 3.5, 10.1, 12.6, 13.6, 17.2
- Additional 3 (structured concurrency) → 6.5, 7.4–7.5, 10.1, 11.5, 12.7–12.9, 13.1, 17.2
- Additional 4 (scoped values) → 1.1, 1.5–1.6, 2.1, 6.5, 10.1, 11.7, 13.1, 17.2
- Additional 5 (independent Spring services) → 1.1, 2.1, 3.1, 4.1, 5.1, 6.1, 7.1, 8.1, 10.1, 11.1, 12.1, 13.1
- Additional 6 (GraphQL gateway) → 13.1, 14.3, 17.4
- Additional 7 (versioned gRPC contracts) → 6.5, 7.4, 10.2, 10.5, 11.7, 12.9, 13.1
- Additional 8 (PostgreSQL system of record) → 1.1, 1.7, 5.1–5.8, 6.1, 7.1–7.6, 8.6
- Additional 9 (MongoDB journals/AI history) → 4.1–4.5, 10.4, 11.7
- Additional 10 (Redis cache/session/limits/locks) → 1.2, 1.7, 2.3, 3.5, 5.8, 16.4
- Additional 11 (Qdrant vector search) → 10.1, 10.4, 11.4–11.7, 17.6
- Additional 12 (WebRTC and HLS) → 12.2, 12.5, 12.6
- Additional 13 (Besu and Web3j) → 8.1–8.6, 17.5
- Additional 14 (JavaFX desktop) → 15.1–15.4
- Additional 15 (Flutter mobile) → 16.1–16.4
- Additional 16 (Kafka/Pulsar event backbone) → 3.1–3.5, 8.3, 12.4, 12.6, 13.6
- Additional 17 (transactional outbox) → 3.1, 3.4–3.5, 6.3, 8.3, 10.5–10.6, 11.6, 12.4, 12.9, 13.6
- Additional 18 (OpenTelemetry stack) → 1.1, 2.1–2.3, 3.1, 5.1, 6.1, 7.1, 8.1, 10.1, 11.1, 12.1, 13.1, 13.6, 18.2
- Additional 19 (existing monorepo scaffold) → 1.1, 18.2

## Epic List

### Epic 1: Account Identity & Access

Users can register, log in (including via OAuth2/OIDC and passkeys), and manage their own sessions and authorization — the foundation every other epic builds on.

- **FRs covered:** FR6, FR7, FR8, FR9, FR10, FR11, FR12
- **Status:** Partially done — registration (FR6), first-party email/password login (FR7), the configured OAuth2/OIDC authorization-code flow (FR8), passkey/WebAuthn assertion login (FR9), and JWT issuance/refresh verification (FR10) exist in `identity-service`; passkey credential registration/provisioning, RBAC/ABAC, and user-facing session management are not yet built.
- **Implementation notes:** Identity-service establishes the first authentication, structured
  logging, metrics, tracing, and distributed rate-limit patterns. Future gateway and client stories
  must consume these decisions rather than reimplementing account or session policy.

### Story 1.1: Account registration foundation [PARTIAL]

As a new LifeOS user,
I want to create an account with a validated email address and display name,
So that I have an identity to use across the platform.

**Traceability:** FR6; NFR12–NFR16, NFR21, NFR25–NFR27; ADR-001, ADR-002, ADR-004, ADR-005, ADR-008, ADR-018.

**Acceptance Criteria:**

**Given** a non-blank, valid email and display name
**When** the client submits `POST /api/v1/accounts`
**Then** the service creates one account, returns `201 Created`, and exposes a stable account identifier and `Location` header
**And** the email uniqueness invariant is enforced transactionally.

**Given** malformed input or an email already registered
**When** the request is submitted
**Then** the service returns `400 Bad Request` or `409 Conflict` without leaking credentials or database details.

**Given** the service is deployed
**When** health, readiness, and liveness probes and a representative request are exercised
**Then** the service emits correlated structured logs, metrics, and traces with secrets and personal security data redacted.

### Story 1.2: First-party email/password login [DONE]

As a registered user,
I want to log in with my email and password,
So that I can start an authenticated LifeOS session.

**Traceability:** FR7; NFR3, NFR9, NFR21–NFR26; ADR-010, ADR-020.

**Acceptance Criteria:**

**Given** an active account with a valid Argon2id password hash
**When** the user submits valid credentials to the versioned login endpoint
**Then** the service authenticates the account and returns the session/token result defined by ADR-020.

**Given** an unknown email, invalid password, disabled account, or malformed request
**When** login is attempted
**Then** the service returns the same generic authentication failure shape and does not disclose whether the account exists.

**Given** repeated failures from a user or client
**When** the configured threshold is exceeded
**Then** Redis-backed rate limiting applies a bounded response and emits an auditable security event without logging the password.

**Implementation notes:** The endpoint is `POST /api/v1/auth/login`. Active credentials are stored
in `password_credential` as Argon2id hashes; session metadata and a SHA-256 access-token digest are
 stored durably in `auth_session`; redacted outcomes are stored in `security_audit_event`. Redis
attempt counters use atomic `INCR`/`EXPIRE` with hashed email/address material and fail closed on
dependency errors. Argon2id verification is also bounded per service instance with a semaphore and
 short acquisition timeout. Story 1.5 owns the implemented refresh-token rotation, asymmetric
 signing/JWKS, and verification middleware; user-facing session listing/revocation remains Story 1.7.

### Story 1.3: OAuth2/OIDC login

As a user,
I want to sign in with a supported OAuth2/OIDC provider,
So that I can use an established identity without sharing its password with LifeOS.

**Traceability:** FR8; NFR3, NFR17, NFR21, NFR23, NFR25–NFR27; ADR-020.

**Acceptance Criteria:**

**Given** a supported provider and a client-generated PKCE challenge
**When** the user completes the authorization-code flow
**Then** the identity service validates issuer, audience, state, nonce, and PKCE, links the provider subject according to policy, and creates a LifeOS session.

**Given** an invalid, expired, reused, or mismatched callback
**When** the callback is received
**Then** authentication is rejected, no account link or session is created, and the failure is audit logged without exposing provider tokens.

### Story 1.4: Passkey/WebAuthn login [DONE]

As a registered user,
I want to authenticate with a passkey,
So that I can log in without entering a password on a supported device.

**Traceability:** FR9; NFR3, NFR18, NFR21, NFR23, NFR25–NFR27; ADR-020.

**Acceptance Criteria:**

**Given** a registered WebAuthn credential
**When** the user completes a challenge from the correct origin and relying-party id
**Then** the identity service verifies the assertion and creates a LifeOS session without receiving or storing a private key.

**Given** a wrong origin/RP id, invalid signature, stale challenge, counter regression, or replayed challenge
**When** authentication is attempted
**Then** the service rejects the attempt and records a redacted security-audit event.

**Implementation notes:** `POST /api/v1/auth/passkey/options` creates a username-less assertion
request and returns an opaque challenge handle plus browser `publicKey` options. Redis stores the
exact Yubico `AssertionRequest` for five minutes by default and consumes it with atomic GET+DEL
semantics. `POST /api/v1/auth/passkey/assertion` validates the exact configured origin and RP ID,
requires configured user verification, checks the registered public key and authenticator counter,
advances the counter with a conditional PostgreSQL update, and creates a `PASSKEY` session through
the shared ADR-020 authority. Passkey endpoints share the distributed attempt limiter and emit
redacted success, rejection, rate-limit, dependency, and session-capacity audit events. Private
keys never enter the service; credential registration/provisioning remains a separate authenticated
step-up story.

### Story 1.5: JWT issuance and verification [DONE]

As an authenticated client,
I want a short-lived access token and a rotating refresh token,
So that downstream services can authorize requests without sharing passwords.

**Traceability:** FR10; NFR3, NFR5, NFR20–NFR23, NFR25–NFR27; ADR-004, ADR-007, ADR-020.

**Acceptance Criteria:**

**Given** a successful first-party, OIDC, or passkey authentication
**When** the session is created or refreshed
**Then** the service issues a signed JWT with issuer, audience, subject, session id, claims, and bounded expiry plus a one-time opaque refresh token whose hash is stored.

**Given** an expired, malformed, wrong-audience, wrong-issuer, or revoked token
**When** a protected service validates it
**Then** the request is rejected with an appropriate authentication error and no sensitive token material is logged.

**Given** two concurrent refresh requests using the same refresh token
**When** both reach the identity service
**Then** at most one successor token is created, one matching idempotent retry may return that same
committed response, and mismatched or repeated reuse revokes the session family.

**Implementation notes:** The shared session authority issues a bounded access JWT with configured
issuer/audience, subject, session id, authentication method, and expiry claims for password, OIDC,
and passkey authentication. Production deployments use configured RSA signing material and expose
the public verification key through JWKS; the existing HMAC path remains a local/test compatibility
mode. Refresh credentials are high-entropy opaque values. PostgreSQL stores only token digests,
durable token-family state, consumed-token replay evidence, and a short-lived encrypted idempotency
envelope. A pessimistic family-row lock linearizes concurrent refresh requests; one matching retry
is returned once, while mismatched or repeated reuse revokes the family. JWT validation performs
signature/claims checks followed by the durable session check and returns generic failures without
logging token material. See [`docs/api/identity-service.md`](api/identity-service.md) and
[`docs/diagrams/identity-jwt.md`](diagrams/identity-jwt.md).

### Story 1.6: RBAC and ABAC authorization decisions

As a LifeOS service,
I want a consistent authorization decision for a user and resource,
So that authenticated users can access only permitted data and actions.

**Traceability:** FR11; NFR19–NFR26; ADR-004, ADR-005, ADR-020.

**Acceptance Criteria:**

**Given** a valid authenticated subject and a role/attribute policy
**When** a service requests an authorization decision
**Then** the decision evaluates both role and resource/tenant attributes and returns a deterministic allow or deny result.

**Given** a missing role, failed attribute condition, cross-user resource, or stale subject
**When** access is attempted
**Then** the action is denied, the denial is auditable, and no resource existence is disclosed beyond the service contract.

### Story 1.7: Device and session management

As an authenticated user,
I want to view and revoke my active devices and sessions,
So that I can recover quickly from a lost device or suspicious login.

**Traceability:** FR12; NFR5, NFR9, NFR12, NFR21–NFR25; ADR-008, ADR-010, ADR-020.

**Acceptance Criteria:**

**Given** multiple active sessions
**When** the user requests their session list
**Then** the service returns only that user's sessions with safe device metadata, created/last-used times, expiry, and revocation state.

**Given** the user revokes one session or all other sessions
**When** the revocation is committed
**Then** subsequent access and refresh attempts for the affected session fail, including after Redis restart, while unrelated sessions remain valid.

**Given** a repeated or concurrent revoke request
**When** it is processed
**Then** the operation is idempotent, bounded by explicit timeouts, and produces one coherent audit outcome.

### Epic 2: Unified Platform Gateway

Users interact with LifeOS through one coherent, reliable entry point rather than hitting fragile individual services directly — requests are authenticated, rate-limited, and traceable end-to-end.

- **FRs covered:** FR1, FR2, FR3, FR4
- **Implementation notes:** Depends on Epic 1 for the auth decisions it enforces. Covers FR4 (correlation IDs), NFR9 (rate limiting), and NFR13 (OpenTelemetry distributed tracing) at the edge.

### Story 2.1: REST routing and correlation IDs

As a client,
I want one external gateway to route requests to the correct service,
So that clients do not need to know internal service topology.

**Traceability:** FR1, FR4; NFR3, NFR10, NFR12–NFR16; ADR-004, ADR-005, ADR-018.

**Acceptance Criteria:**

**Given** a request to a versioned public route
**When** the gateway receives it
**Then** it routes only to the configured upstream, preserves the public contract, and returns a controlled error for an unknown route.

**Given** a request with or without a correlation id
**When** it crosses the gateway
**Then** exactly one validated correlation/trace context is propagated to logs, downstream calls, and response headers.

### Story 2.2: Gateway authentication enforcement

As a platform owner,
I want the gateway to enforce authentication on protected routes,
So that unauthenticated traffic cannot reach user data services.

**Traceability:** FR2; NFR3, NFR9, NFR17–NFR23, NFR25–NFR26; ADR-020.

**Acceptance Criteria:**

**Given** a protected route and a valid JWT
**When** the request arrives
**Then** the gateway validates issuer, audience, expiry, and required claims before forwarding the request with the authenticated subject context.

**Given** a missing, malformed, expired, or revoked token
**When** the request arrives
**Then** the gateway returns `401` or `403` without forwarding it and emits a redacted security metric.

### Story 2.3: Gateway rate limiting and dependency isolation

As a platform owner,
I want bounded per-client rate limits and isolated upstream failures,
So that abusive or failing traffic cannot exhaust the platform.

**Traceability:** FR3; NFR1–NFR4, NFR9–NFR16, NFR21, NFR26; ADR-010, ADR-018.

**Acceptance Criteria:**

**Given** requests from a user, anonymous client, or gateway route
**When** the configured limit is exceeded
**Then** Redis-backed limiting returns `429` with safe retry guidance and exposes limit, rejection, and latency metrics.

**Given** an unavailable or slow upstream
**When** the gateway calls it
**Then** explicit timeouts, circuit breaking, and bulkhead limits prevent unbounded resource use and return a documented degraded response.

### Epic 3: Reminders & Notifications

Users receive timely email, push, and real-time notifications, with reliable delivery even when a channel is temporarily unavailable.

- **FRs covered:** FR69, FR70, FR71, FR72, FR73, FR74
- **Implementation notes:** Depends on Epic 1. First natural home for the outbox pattern (NFR7), retry/backoff (NFR2), and dead-letter handling (NFR6) — later epics (Calendar, Video) call into this one rather than reimplementing delivery.

### Story 3.1: Email notification delivery

As a user,
I want important LifeOS events delivered by email,
So that I can act even when I am not inside the application.

**Traceability:** FR69; NFR3, NFR5, NFR7, NFR12–NFR16, NFR21, NFR25–NFR27; ADR-016–ADR-018.

**Acceptance Criteria:**

**Given** a validated notification event with an eligible email channel
**When** the notification service consumes it
**Then** it renders a versioned template, sends through the configured provider, records an idempotency key, and emits delivery telemetry.

**Given** the database write and event publication occur together
**When** the transaction commits
**Then** the event is published through a transactional outbox rather than a dual write.

### Story 3.2: Push notification delivery

As a user,
I want reminders delivered to my registered mobile devices,
So that time-sensitive actions reach me away from the web app.

**Traceability:** FR70; NFR3, NFR5, NFR10, NFR12–NFR16, NFR21, NFR25–NFR27.

**Acceptance Criteria:**

**Given** an opted-in device token and a notification event
**When** the push channel handles it
**Then** it sends a privacy-safe payload, records provider outcome, and does not reveal document, finance, or health content in notification previews by default.

**Given** an invalid or expired device token
**When** the provider rejects delivery
**Then** the token is disabled idempotently and the event follows the documented retry or dead-letter policy.

### Story 3.3: Real-time notification stream

As an active user,
I want notifications delivered through a live stream,
So that the application can update without polling.

**Traceability:** FR71; NFR3, NFR6, NFR9–NFR16, NFR20, NFR23, NFR26–NFR27.

**Acceptance Criteria:**

**Given** an authenticated client opens a WebSocket or SSE connection
**When** an eligible event is published
**Then** only events for that subject are delivered in order with bounded buffering and heartbeat/timeout behavior.

**Given** the client disconnects or the stream consumer falls behind
**When** delivery resumes
**Then** the service applies backpressure and a bounded replay/resync policy instead of allocating an unbounded queue.

### Story 3.4: Reminder channel fanout

As a user,
I want one reminder to reach the channels I have enabled,
So that I receive it reliably without duplicate business events.

**Traceability:** FR72; NFR5–NFR11, NFR13–NFR16, NFR25–NFR27; ADR-016, ADR-017.

**Acceptance Criteria:**

**Given** a reminder event and user channel preferences
**When** fanout is requested
**Then** the service creates one idempotent delivery per eligible channel with a shared correlation id and per-channel outcome.

**Given** one channel is unavailable
**When** fanout runs
**Then** healthy channels proceed independently and the failed channel follows retry/dead-letter policy without duplicating successful deliveries.

### Story 3.5: Notification retry and dead-letter handling

As an operator,
I want failed notification work retried and permanently failed work isolated,
So that transient outages recover without silently dropping user communication.

**Traceability:** FR73, FR74; NFR1–NFR4, NFR6–NFR12, NFR13–NFR16, NFR21, NFR25–NFR28.

**Acceptance Criteria:**

**Given** a transient provider failure
**When** delivery fails
**Then** the service retries with bounded exponential backoff and jitter, explicit timeouts, and an idempotency key.

**Given** the retry budget is exhausted or the failure is permanent
**When** the delivery is classified
**Then** it is moved to a durable dead-letter path with the reason, correlation id, and safe replay controls, and an alertable metric is emitted.

### Epic 4: Personal Profile & Preferences

Users maintain a personal profile, preferences, household members, privacy settings, and AI personalization settings.

- **FRs covered:** FR13, FR14, FR15, FR16, FR17
- **Implementation notes:** Depends on Epic 1. Straightforward CRUD domain — first candidate for MongoDB usage (ADR-009) since preferences/household data is semi-structured.

### Story 4.1: Personal profile management

As an authenticated user,
I want to view and update my personal profile,
So that LifeOS can represent me accurately across services.

**Traceability:** FR13; NFR3, NFR5, NFR12–NFR16, NFR21–NFR26; ADR-005, ADR-009.

**Acceptance Criteria:**

**Given** an authenticated user
**When** the user reads or updates their profile
**Then** only their profile is returned or changed, validated fields are persisted atomically, and the response is versioned.

**Given** invalid, oversized, or unauthorized profile data
**When** it is submitted
**Then** the service rejects it without partial writes or disclosure of another user's profile.

### Story 4.2: User preferences

As an authenticated user,
I want to manage preferences such as locale, timezone, and notification defaults,
So that the platform behaves consistently for me.

**Traceability:** FR14; NFR3, NFR5, NFR12–NFR16, NFR21–NFR26; ADR-009.

**Acceptance Criteria:**

**Given** a supported preference value
**When** it is saved
**Then** the preference is scoped to the authenticated user, validated, and available to dependent services through a versioned contract.

**Given** an unsupported or conflicting value
**When** it is submitted
**Then** the service returns a field-level validation error and preserves the previous valid value.

### Story 4.3: Household and family members

As an account owner,
I want to manage household members with explicit relationships,
So that shared-life planning can be represented without sharing all private data.

**Traceability:** FR15; NFR5, NFR19, NFR21–NFR26.

**Acceptance Criteria:**

**Given** an authenticated account owner
**When** a member is invited, updated, or removed
**Then** the relationship and its permissions are persisted idempotently and the member sees only data authorized by policy.

**Given** a cross-account or unauthorized mutation
**When** it is attempted
**Then** the service denies it and records the security-relevant decision without revealing membership details.

### Story 4.4: Privacy settings

As an authenticated user,
I want to control privacy and data-sharing settings,
So that sensitive LifeOS data is used only as I permit.

**Traceability:** FR16; NFR19, NFR21–NFR26.

**Acceptance Criteria:**

**Given** a supported privacy setting
**When** the user changes it
**Then** the setting takes effect for new reads and processing decisions, is audit logged, and cannot broaden access beyond policy limits.

**Given** a setting that conflicts with a mandatory security or legal invariant
**When** it is submitted
**Then** the service rejects it with an actionable explanation and keeps the last valid value.

### Story 4.5: AI personalization settings

As an authenticated user,
I want to control how the AI assistant personalizes responses,
So that recommendations match my preferences and consent.

**Traceability:** FR17; NFR3, NFR5, NFR19, NFR21–NFR26; ADR-009.

**Acceptance Criteria:**

**Given** a personalization preference and explicit consent state
**When** it is saved
**Then** the AI orchestrator can consume the versioned setting and can identify whether personalization is allowed.

**Given** personalization is disabled
**When** an AI request is processed
**Then** disallowed personal context is not retrieved or used, and the decision is observable without exposing the context itself.

### Epic 5: Task & Goal Management

Users create tasks and goals, track habits and routines, express dependencies between them, and see a valid execution order.

- **FRs covered:** FR18, FR19, FR20, FR21, FR22, FR23, FR24, FR25
- **Status:** Partially done — goal create/list (FR19) and dependency-order computation (FR25, the algorithm itself is correct and complete) exist in `task-goal-service`. Not actually done despite earlier drafts of this doc claiming otherwise: there is no `Task` entity at all (FR18), goals have no update/delete (FR19 is create+list only), and dependency data isn't persisted against real goals (FR22 computes an order from submitted data but doesn't store a dependency relationship). Habits, routines, milestones, and recurrence (FR20, FR21, FR23, FR24) are not yet built either.
- **Implementation notes:** Depends on Epic 1. The existing dependency-ordering implementation reimplements Kahn's algorithm directly rather than calling a shared Algorithm Engine — note this as a future consolidation opportunity once Epic 9 exists, not a blocker.

### Story 5.1: Task lifecycle

As an authenticated user,
I want to create, update, complete, and view tasks,
So that actionable work is tracked in one place.

**Traceability:** FR18; NFR3, NFR5, NFR12–NFR16, NFR19, NFR21–NFR27; ADR-001, ADR-002, ADR-005, ADR-008.

**Acceptance Criteria:**

**Given** valid task fields and an authenticated subject
**When** a task is created, updated, completed, or listed
**Then** the service persists the task under that user, returns a versioned response, and enforces valid state transitions.

**Given** an invalid transition, duplicate idempotency key, or cross-user task id
**When** the command is submitted
**Then** the service rejects it without a partial write or resource disclosure.

### Story 5.2: Goal lifecycle [PARTIAL]

As an authenticated user,
I want to create, update, complete, and archive goals,
So that long-term outcomes remain actionable and measurable.

**Traceability:** FR19; NFR3, NFR5, NFR12–NFR16, NFR19, NFR21–NFR27; ADR-008.

**Acceptance Criteria:**

**Given** valid goal data
**When** the user creates, lists, updates, completes, or archives a goal
**Then** the service persists the lifecycle state and returns only goals in the authenticated user's scope.

**Given** an invalid transition, duplicate command, or missing goal
**When** a mutation is attempted
**Then** the service returns a deterministic error and preserves the prior state.

### Story 5.3: Habit tracking

As an authenticated user,
I want to define habits and record occurrences,
So that I can see consistency over time.

**Traceability:** FR20; NFR3, NFR5, NFR12–NFR16, NFR21–NFR27.

**Acceptance Criteria:**

**Given** a valid habit cadence
**When** the user records an occurrence or correction
**Then** the event is idempotent, timestamped in the user's timezone, and contributes to a deterministic streak/trend calculation.

**Given** an invalid date, duplicate occurrence, or unauthorized habit id
**When** it is submitted
**Then** the service rejects it without altering history.

### Story 5.4: Routine definitions

As an authenticated user,
I want to define routines made of ordered activities,
So that repeated work can be executed consistently.

**Traceability:** FR21, FR24; NFR3, NFR5, NFR12–NFR16, NFR21–NFR27.

**Acceptance Criteria:**

**Given** valid routine activities and recurrence rules
**When** the routine is created or updated
**Then** the service validates ordering and recurrence bounds and persists a versioned routine definition.

**Given** an impossible recurrence, cycle, or invalid activity reference
**When** the definition is submitted
**Then** it is rejected with actionable validation errors and no partial update.

### Story 5.5: Persisted task and goal dependencies

As an authenticated user,
I want to express dependencies between real tasks and goals,
So that planning reflects the work I actually own.

**Traceability:** FR22; NFR3, NFR5, NFR19, NFR21–NFR27; ADR-008.

**Acceptance Criteria:**

**Given** two accessible task/goal nodes
**When** the user creates or removes a dependency
**Then** the relationship is persisted transactionally and duplicate edges are idempotent.

**Given** a self-edge, cycle, missing node, or cross-user reference
**When** the dependency is submitted
**Then** the service rejects it before commit and explains the violated invariant.

### Story 5.6: Dependency-respecting execution order [DONE/PARTIAL integration]

As an authenticated user,
I want my persisted goals and tasks returned in a valid dependency order,
So that I know what can be executed next.

**Traceability:** FR25; NFR3, NFR12–NFR16, NFR21–NFR28; `docs/algorithms/topological-sort-goal-dependencies.md`.

**Acceptance Criteria:**

**Given** an acyclic persisted dependency graph
**When** an execution order is requested
**Then** every dependency appears before its dependent node, with deterministic tie-breaking and O(V+E) time complexity.

**Given** a cyclic or oversized graph
**When** ordering is requested
**Then** the service returns a bounded validation/error response and does not emit a misleading partial order.

### Story 5.7: Milestones

As an authenticated user,
I want to attach milestones and completion criteria to goals,
So that progress can be measured before the final outcome.

**Traceability:** FR23; NFR3, NFR5, NFR19, NFR21–NFR27.

**Acceptance Criteria:**

**Given** an accessible goal and valid milestone data
**When** a milestone is created, reordered, completed, or reopened
**Then** the service persists the state and computes goal progress from the defined criteria.

**Given** an invalid order, duplicate milestone, or unauthorized goal
**When** the command is submitted
**Then** it is rejected without partial progress changes.

### Story 5.8: Recurring activities

As an authenticated user,
I want recurring activities materialized safely from a schedule,
So that future work is predictable without duplicate tasks.

**Traceability:** FR24; NFR2, NFR3, NFR5, NFR10–NFR12, NFR21–NFR28.

**Acceptance Criteria:**

**Given** a bounded recurrence rule and generation window
**When** the scheduler materializes occurrences
**Then** it creates only the expected instances, uses an idempotency key, and records the source rule.

**Given** a missed run or scheduler retry
**When** materialization resumes
**Then** it recovers within the configured window without unbounded catch-up or duplicate occurrences.

### Epic 6: Calendar & Scheduling

Users manage calendar events, block time, get reminded before events, and get conflict/optimization help.

- **FRs covered:** FR26, FR27, FR28, FR29, FR30
- **Implementation notes:** Depends on Epic 1 and Epic 3 (FR28 reminders call the Notification epic rather than reimplementing delivery).

### Story 6.1: Calendar event lifecycle

As an authenticated user,
I want to create and manage calendar events,
So that my commitments are represented in LifeOS.

**Traceability:** FR26; NFR3, NFR5, NFR12–NFR16, NFR19, NFR21–NFR27; ADR-005, ADR-008.

**Acceptance Criteria:**

**Given** valid start/end times, timezone, and event details
**When** the user creates, updates, cancels, or lists events
**Then** the service persists normalized times, enforces ownership, and returns a versioned event representation.

**Given** an invalid interval, timezone, duplicate command, or unauthorized event
**When** it is submitted
**Then** the service rejects it without a partial write.

### Story 6.2: Time blocking

As an authenticated user,
I want to reserve calendar time for a task, goal, or focus block,
So that intended work becomes actionable on my schedule.

**Traceability:** FR27; NFR3, NFR5, NFR19, NFR21–NFR27.

**Acceptance Criteria:**

**Given** an accessible task/goal and free time range
**When** a block is created or updated
**Then** the calendar stores the link, duration, timezone, and ownership without silently moving other events.

**Given** an overlapping or invalid block
**When** it is submitted
**Then** the service reports the conflict and offers no implicit overwrite.

### Story 6.3: Event reminders

As an authenticated user,
I want reminders before calendar events,
So that I can prepare and arrive on time.

**Traceability:** FR28; NFR1–NFR12, NFR13–NFR16, NFR21, NFR25–NFR27; ADR-010, ADR-016, ADR-017, ADR-018.

**Acceptance Criteria:**

**Given** an event and a valid reminder preference
**When** the reminder becomes due
**Then** the calendar publishes one idempotent notification event for the Notification Service with the user's timezone and correlation id.

**Given** the notification dependency is unavailable
**When** reminder publication fails
**Then** the calendar applies bounded retry/outbox behavior and does not block unrelated event reads or writes.

### Story 6.4: Schedule conflict detection

As an authenticated user,
I want overlapping commitments identified,
So that I can resolve conflicts before they disrupt my day.

**Traceability:** FR29; NFR3, NFR10–NFR16, NFR21–NFR28.

**Acceptance Criteria:**

**Given** events and time blocks in a requested window
**When** conflict detection runs
**Then** it returns all overlapping intervals in deterministic order, including timezone-normalized boundaries.

**Given** adjacent intervals that only share an endpoint
**When** detection runs
**Then** they are not reported as overlapping unless the user's configured policy says otherwise.

### Story 6.5: Calendar optimization suggestions

As an authenticated user,
I want suggestions for resolving conflicts and protecting focus time,
So that my calendar better reflects my goals.

**Traceability:** FR30; NFR1–NFR4, NFR10–NFR16, NFR19, NFR21–NFR28; ADR-003, ADR-004, ADR-006, ADR-007.

**Acceptance Criteria:**

**Given** a user's calendar, task/goal priorities, and explicit preferences
**When** optimization is requested
**Then** the service returns explainable, bounded suggestions without mutating the calendar.

**Given** an unavailable recommendation dependency
**When** optimization runs
**Then** the service returns a degraded response with the original calendar intact and an observable dependency failure.

### Epic 7: Personal Finance & Budgeting

Users manage budgets, record and categorize transactions, and get spending insights and forecasts.

- **FRs covered:** FR31, FR32, FR33, FR34, FR35, FR36
- **Implementation notes:** Depends on Epic 1. PostgreSQL system-of-record domain (ADR-008) — financial correctness (idempotent posting, NFR5) matters most here.

### Story 7.1: Budget lifecycle

As an authenticated user,
I want to create and manage budgets by period and category,
So that I can make spending decisions against explicit limits.

**Traceability:** FR31; NFR3, NFR5, NFR12–NFR16, NFR19, NFR21–NFR28; ADR-008.

**Acceptance Criteria:**

**Given** valid currency, period, and category allocations
**When** a budget is created or updated
**Then** the service persists one versioned budget per scope, validates totals and currency, and returns a stable representation.

**Given** invalid amounts, overlapping active periods, or an unauthorized budget
**When** the command is submitted
**Then** the service rejects it without a partial financial write.

### Story 7.2: Transaction recording

As an authenticated user,
I want to record income and expenses,
So that my financial history is complete and auditable.

**Traceability:** FR32; NFR3, NFR5, NFR12–NFR16, NFR19, NFR21–NFR28; ADR-008.

**Acceptance Criteria:**

**Given** a valid transaction and idempotency key
**When** it is submitted
**Then** exactly one immutable financial record is created in the user's scope and its amount/currency/date invariants are enforced.

**Given** a retry with the same idempotency key or a malformed transaction
**When** it is submitted
**Then** the service returns the original result or a validation error without double-posting.

### Story 7.3: Transaction categorization

As an authenticated user,
I want transactions categorized consistently,
So that budgets and insights use meaningful groups.

**Traceability:** FR33; NFR3, NFR5, NFR19, NFR21–NFR27.

**Acceptance Criteria:**

**Given** a transaction and a valid category
**When** the category is assigned or corrected
**Then** the change is versioned, attributable, and reflected in later aggregations.

**Given** an unsupported category or transaction outside the user's scope
**When** categorization is attempted
**Then** the service rejects it without altering the transaction history.

### Story 7.4: Spending insights

As an authenticated user,
I want spending and income summarized over selected periods,
So that I can understand where money is going.

**Traceability:** FR34; NFR1–NFR4, NFR10–NFR16, NFR19, NFR21–NFR28; ADR-003, ADR-004, ADR-007, ADR-008.

**Acceptance Criteria:**

**Given** a bounded date range and authorized financial data
**When** insights are requested
**Then** the service returns deterministic category totals, period comparisons, and source timestamps without exposing other users' data.

**Given** incomplete or unavailable enrichment data
**When** insights are computed
**Then** the service returns clearly labeled partial results rather than inventing values.

### Story 7.5: Spending and income forecasting

As an authenticated user,
I want a forecast of future spending and income,
So that I can plan ahead with explicit uncertainty.

**Traceability:** FR35; NFR1–NFR4, NFR10–NFR16, NFR21–NFR28; ADR-003, ADR-004, ADR-007.

**Acceptance Criteria:**

**Given** sufficient historical data and a bounded forecast horizon
**When** a forecast is requested
**Then** the service returns a versioned forecast with methodology, confidence/coverage indicators, and source window.

**Given** insufficient, contradictory, or stale data
**When** forecasting runs
**Then** the service refuses false precision and explains why a forecast is unavailable or limited.

### Story 7.6: Financial goal tracking

As an authenticated user,
I want to track progress toward financial goals,
So that I can connect daily transactions to longer-term outcomes.

**Traceability:** FR36; NFR3, NFR5, NFR19, NFR21–NFR27; ADR-008.

**Acceptance Criteria:**

**Given** a target amount, currency, deadline, and contribution rule
**When** a financial goal is created or updated
**Then** the service validates the rule and calculates progress from authorized transactions and explicit contributions.

**Given** currency mismatch, invalid target, or cross-user transaction reference
**When** the goal is changed
**Then** the request is rejected without corrupting progress history.

### Epic 8: Blockchain Trust & Verification

Users can get tamper-evident proof that a document, credential, or achievement is genuine and unaltered, without exposing private data on-chain.

- **FRs covered:** FR63, FR64, FR65, FR66, FR67, FR68
- **Implementation notes:** Depends on Epic 1. Standalone utility other epics (Document Vault, Video) call into for proof requests — built once, consumed repeatedly.

### Story 8.1: Document hash proof

As an authenticated user,
I want a canonical hash generated for a document,
So that later verification can detect content changes without exposing the document.

**Traceability:** FR63; NFR3, NFR5, NFR12–NFR16, NFR21–NFR28; ADR-008, ADR-013, ADR-018.

**Acceptance Criteria:**

**Given** a byte stream and canonical metadata
**When** proof generation runs
**Then** the service returns a deterministic cryptographic digest and algorithm identifier without persisting private content on the ledger.

**Given** an empty, oversized, or unreadable input
**When** hashing is requested
**Then** the service returns a bounded validation/error response and emits no partial proof.

### Story 8.2: Merkle proof generation

As an authenticated user,
I want a Merkle proof for a batch of document hashes,
So that many proofs can be verified against one root efficiently.

**Traceability:** FR64; NFR3, NFR5, NFR11–NFR16, NFR21–NFR28; ADR-013.

**Acceptance Criteria:**

**Given** a bounded ordered set of unique leaf hashes
**When** a Merkle tree is built
**Then** the service returns the root and per-leaf proofs using documented odd-node and ordering rules.

**Given** a tampered leaf, invalid proof path, or batch above the configured bound
**When** verification is requested
**Then** verification fails deterministically without allocating unbounded memory.

### Story 8.3: Blockchain root anchoring

As an authenticated user,
I want a Merkle root anchored to the private Besu network,
So that the proof has a tamper-evident external timestamp.

**Traceability:** FR65; NFR1–NFR8, NFR11–NFR16, NFR20–NFR23, NFR25–NFR28; ADR-013, ADR-016, ADR-017, ADR-018.

**Acceptance Criteria:**

**Given** a valid root and minimal non-sensitive metadata
**When** anchoring is requested
**Then** Web3j submits one idempotent transaction, returns a tracking id, and never writes document contents or private identifiers on-chain.

**Given** a node timeout, rejected transaction, or duplicate request
**When** anchoring runs
**Then** bounded retry/outbox handling preserves one logical request and exposes confirmation status without falsely reporting success.

### Story 8.4: Credential proof verification

As a verifier,
I want to verify a credential against an anchored proof,
So that I can detect tampering without receiving the issuer's private data.

**Traceability:** FR66; NFR3, NFR10–NFR16, NFR20–NFR26, NFR28; ADR-013.

**Acceptance Criteria:**

**Given** a credential digest, proof path, root, and anchor reference
**When** verification runs
**Then** the service checks cryptographic validity, chain/ledger status, and metadata consistency and returns a reasoned result.

**Given** a mismatched digest, revoked anchor, or unavailable ledger
**When** verification runs
**Then** the result is `invalid` or `indeterminate`, never `valid` by fallback.

### Story 8.5: AI audit hash anchoring

As an operator,
I want selected AI audit records anchored by hash,
So that audit integrity can be independently checked.

**Traceability:** FR67, FR59; NFR5–NFR8, NFR20–NFR25, NFR27–NFR28; ADR-013, ADR-016, ADR-017.

**Acceptance Criteria:**

**Given** a canonicalized, redacted AI audit record
**When** an anchor is requested
**Then** only its hash and minimal non-sensitive metadata are sent to the ledger and the local record retains the correlation/anchor reference.

**Given** a record containing secrets or private prompt/context content
**When** canonicalization runs
**Then** the request is rejected or redacted before any ledger call.

### Story 8.6: Goal-achievement certificate proofs

As an authenticated user,
I want a completed goal to produce a verifiable certificate proof,
So that achievement claims can be checked without publishing private goal data.

**Traceability:** FR68; NFR3, NFR5, NFR19–NFR28; ADR-008, ADR-013.

**Acceptance Criteria:**

**Given** an authorized completed goal and its immutable achievement facts
**When** certificate issuance is requested
**Then** the service creates a canonical proof payload, anchors only the permitted hash metadata, and returns a verification reference.

**Given** an incomplete, changed, or unauthorized goal
**When** issuance is requested
**Then** it is rejected and no certificate or ledger transaction is created.

### Epic 9: Algorithm Engine & Interview Readiness

As an engineer using this project for FAANG-style interview preparation, reusable, benchmarked algorithm implementations exist that power real product features and double as interview-practice material — a secondary persona this project explicitly serves (see REQUIREMENTS.md "Career Goals This Project Supports").

- **FRs covered:** FR60, FR61, FR62
- **Implementation notes:** No hard dependency on other epics; can be built anytime, but delivers most value once at least one domain epic (Task/Goal, Calendar, Finance) exists to point its algorithms at as "real product use cases" rather than isolated examples.

### Story 9.1: Reusable algorithm library

As an engineer maintaining LifeOS,
I want reusable planning, optimization, and ranking algorithms,
So that product services share correct implementations instead of duplicating logic.

**Traceability:** FR60; NFR12–NFR16, NFR27–NFR28; ADR-001, ADR-005.

**Acceptance Criteria:**

**Given** a supported algorithm and valid input bounds
**When** the shared API is called
**Then** it returns a deterministic result, documents time/space complexity, and exposes no mutable global state.

**Given** null, malformed, cyclic, or oversized input
**When** the algorithm is called
**Then** it rejects the input predictably without stack or heap exhaustion.

### Story 9.2: Algorithm benchmarking

As an engineer,
I want repeatable algorithm benchmarks,
So that performance claims are measured rather than guessed.

**Traceability:** FR61; NFR27–NFR28; ADR-001.

**Acceptance Criteria:**

**Given** a benchmark fixture, JVM/runtime metadata, and warmup policy
**When** the benchmark runs
**Then** it records methodology, input size, result correctness, latency/throughput, and environment metadata.

**Given** a regression against a documented baseline
**When** comparison runs
**Then** the report identifies the regression without inventing unavailable measurements.

### Story 9.3: Interview-practice examples

As an engineer preparing for interviews,
I want product-backed algorithm examples,
So that I can explain both implementation and system context.

**Traceability:** FR62; NFR27–NFR28; `docs/algorithms/`.

**Acceptance Criteria:**

**Given** each published algorithm example
**When** a reader opens it
**Then** it includes problem framing, correctness rationale, complexity, edge cases, tests, and a real LifeOS use case.

**Given** an example is changed
**When** documentation validation runs
**Then** code, tests, complexity claims, and links remain consistent.

### Epic 10: AI Life Assistant

Users get an AI assistant that gives goal-planning recommendations, financial insights, and session summaries, with every AI decision logged for auditability.

- **FRs covered:** FR53, FR55, FR56, FR57, FR58, FR59
- **Implementation notes:** Depends on Epic 1, Epic 5 (FR55 needs goal data), Epic 7 (FR56 needs finance data). RAG-over-documents (originally FR54) is intentionally NOT in this epic — it's grouped into Epic 11 (Document Vault) instead, since it can't deliver value until documents exist, avoiding a circular dependency between this epic and Document Vault.

### Story 10.1: AI life-assistant interaction surface

As an authenticated user,
I want to ask the LifeOS assistant questions and receive actionable responses,
So that I can coordinate personal planning from one interaction surface.

**Traceability:** FR53; NFR1–NFR4, NFR10–NFR16, NFR19–NFR28; ADR-003, ADR-004, ADR-009, ADR-011, ADR-018.

**Acceptance Criteria:**

**Given** an authenticated conversation request with bounded input
**When** the assistant handles it
**Then** it returns a versioned response tied to the user/session, records latency and provider metadata, and applies safety/policy checks.

**Given** a provider timeout, quota error, unsafe request, or malformed output
**When** generation fails
**Then** the assistant returns a safe degraded response, does not retry unboundedly, and records the failure classification.

### Story 10.2: Goal-planning recommendations

As an authenticated user,
I want AI recommendations grounded in my goals, tasks, and constraints,
So that I can choose a practical next step.

**Traceability:** FR55; NFR1–NFR4, NFR10–NFR16, NFR19, NFR21–NFR28; ADR-003, ADR-004, ADR-007.

**Acceptance Criteria:**

**Given** authorized goal/task context and explicit personalization consent
**When** recommendations are requested
**Then** the assistant returns explainable suggestions with source context ids and does not mutate data without a separate authorized action.

**Given** missing, stale, or unauthorized context
**When** planning runs
**Then** it omits that context and labels limitations rather than fabricating facts.

### Story 10.3: Financial insights

As an authenticated user,
I want AI explanations of my financial patterns,
So that I can make informed budgeting decisions.

**Traceability:** FR56; NFR1–NFR4, NFR10–NFR16, NFR19, NFR21–NFR28; ADR-003, ADR-004, ADR-007, ADR-008.

**Acceptance Criteria:**

**Given** authorized financial aggregates and a bounded question
**When** the assistant responds
**Then** it cites the source period/categories, distinguishes observations from forecasts, and avoids exposing raw unrelated transactions.

**Given** insufficient data or a request for prohibited financial action
**When** the request is processed
**Then** the assistant explains the limitation and does not invoke a write tool.

### Story 10.4: Session and journal summaries

As an authenticated user,
I want my sessions and journals summarized,
So that I can retain the important points without rereading everything.

**Traceability:** FR57; NFR1–NFR4, NFR10–NFR16, NFR19, NFR21–NFR28; ADR-003, ADR-009, ADR-011.

**Acceptance Criteria:**

**Given** authorized source content and a requested summary format
**When** summarization runs
**Then** the output is linked to source ids, is clearly labeled as generated, and does not include content outside the user's scope.

**Given** content is unavailable, sensitive beyond consent, or too large for the budget
**When** summarization runs
**Then** it fails safely with a bounded partial result or actionable error.

### Story 10.5: AI tool calling

As an authenticated user,
I want the assistant to propose and execute permitted LifeOS actions,
So that planning can become work without unsafe automation.

**Traceability:** FR58; NFR1–NFR11, NFR19–NFR27; ADR-003, ADR-004, ADR-007, ADR-017.

**Acceptance Criteria:**

**Given** a tool with a versioned schema and an authorized user intent
**When** the assistant proposes an action
**Then** the system validates arguments, applies authorization, requests confirmation for side effects, and uses an idempotency key for retries.

**Given** invalid arguments, denied policy, timeout, or duplicate execution
**When** the tool is called
**Then** no unauthorized side effect occurs and the conversation records the safe failure.

### Story 10.6: AI decision audit logging

As an operator and user,
I want AI decisions auditable,
So that recommendations and tool actions can be explained and investigated.

**Traceability:** FR59; NFR5–NFR8, NFR13–NFR16, NFR19–NFR25, NFR27–NFR28; ADR-009, ADR-013, ADR-017, ADR-018.

**Acceptance Criteria:**

**Given** an AI request, retrieval, model response, or tool call
**When** processing completes or fails
**Then** the audit record includes prompt-template id, retrieved-context ids, provider/model, output summary, confidence, safety flags, decision, and correlation id.

**Given** an audit record contains secrets or private raw content
**When** it is persisted or exported
**Then** redaction/canonicalization removes prohibited data and preserves enough metadata for investigation.

### Epic 11: Document Vault

Users upload, search, and get AI summaries of their documents, with tamper-evident proof-of-existence available on request.

- **FRs covered:** FR37, FR38, FR39, FR40, FR41, FR42, FR54
- **Implementation notes:** Depends on Epic 1, Epic 8 (FR42), and Epic 10 (FR41, FR54). Upload/metadata/search (FR37–40) can ship as the epic's first stories without waiting on AI or blockchain; FR41/FR42/FR54 are later stories within this same epic once their dependencies exist.

### Story 11.1: Secure document upload

As an authenticated user,
I want to upload a document safely,
So that it becomes available to my private vault.

**Traceability:** FR37; NFR3, NFR5, NFR12–NFR16, NFR21–NFR28; ADR-005, ADR-008, ADR-018.

**Acceptance Criteria:**

**Given** an authenticated upload with a bounded size and supported media type
**When** the upload is submitted
**Then** the service validates content type and safety policy, stores the object through the configured storage boundary, and returns a durable document id.

**Given** a malformed, oversized, suspicious, or interrupted upload
**When** validation or storage fails
**Then** no usable document reference is published and partial objects are cleaned up or quarantined.

### Story 11.2: Document metadata

As an authenticated user,
I want document metadata stored and editable,
So that documents can be organized and understood later.

**Traceability:** FR38; NFR3, NFR5, NFR19, NFR21–NFR27; ADR-008.

**Acceptance Criteria:**

**Given** a document owned by the user
**When** metadata is added or updated
**Then** validated title, tags, timestamps, source, and classification are versioned under that document without changing the object bytes.

**Given** invalid metadata or a cross-user document id
**When** it is submitted
**Then** the service rejects the change without disclosure or partial persistence.

### Story 11.3: Secure storage references

As a platform owner,
I want database records to contain secure object references rather than file bytes,
So that storage can scale independently and private content is not embedded in PostgreSQL.

**Traceability:** FR39; NFR3, NFR5, NFR21–NFR24, NFR26–NFR28; ADR-005, ADR-008.

**Acceptance Criteria:**

**Given** an uploaded object
**When** its record is persisted
**Then** the database stores an opaque, access-controlled reference, checksum, size, and content metadata rather than the raw file.

**Given** an expired or unauthorized download request
**When** a storage reference is resolved
**Then** access is denied and no provider credential or permanent public URL is returned.

### Story 11.4: Document search

As an authenticated user,
I want to search my document metadata and indexed content,
So that I can retrieve the right information quickly.

**Traceability:** FR40; NFR3, NFR10–NFR16, NFR19, NFR21–NFR28; ADR-005, ADR-011.

**Acceptance Criteria:**

**Given** a bounded query and an authenticated user
**When** search runs
**Then** results are filtered by ownership/authorization, paginated deterministically, and include relevance/source metadata.

**Given** search is unavailable or the query is malformed
**When** the request runs
**Then** the service returns a safe degraded/error result without falling back to an unbounded database scan.

### Story 11.5: AI document summaries

As an authenticated user,
I want an AI summary of a document,
So that I can understand it without reading every page first.

**Traceability:** FR41; NFR1–NFR4, NFR10–NFR16, NFR19, NFR21–NFR28; ADR-003, ADR-009, ADR-011, ADR-018.

**Acceptance Criteria:**

**Given** an authorized, readable document and a bounded summary request
**When** summarization runs
**Then** the result is linked to the document/version, labeled generated, and includes source/chunk identifiers used for retrieval.

**Given** an unreadable, oversized, unavailable, or unauthorized document
**When** summarization is requested
**Then** no content leaks and the system returns a bounded, observable failure.

### Story 11.6: Blockchain proof request

As an authenticated user,
I want to request proof-of-existence for a document,
So that its integrity can later be verified.

**Traceability:** FR42; NFR1–NFR8, NFR19–NFR28; ADR-013, ADR-017.

**Acceptance Criteria:**

**Given** an authorized immutable document version
**When** proof is requested
**Then** Document Vault publishes one idempotent proof command containing only the hash/reference needed by Blockchain Trust Ledger.

**Given** a duplicate, changed, or unauthorized version
**When** proof is requested
**Then** the service returns the existing proof status or rejects the request without creating an incorrect anchor.

### Story 11.7: Retrieval-augmented document answers

As an authenticated user,
I want questions answered from my own documents,
So that the AI assistant can use my knowledge without mixing it with another user's data.

**Traceability:** FR54; NFR1–NFR4, NFR10–NFR16, NFR19, NFR21–NFR28; ADR-003, ADR-004, ADR-009, ADR-011.

**Acceptance Criteria:**

**Given** indexed document chunks authorized for the user
**When** a grounded question is asked
**Then** retrieval filters by user/tenant policy, returns source ids, and the answer states when evidence is insufficient.

**Given** vector search or source storage is unavailable
**When** the question is processed
**Then** the assistant refuses unsupported claims and returns a safe degraded response with dependency telemetry.

### Epic 12: Video Coaching & Journaling

Users schedule and join live coaching/journaling video sessions, with recordings, transcription, AI summaries, and automatic follow-up task creation.

- **FRs covered:** FR43, FR44, FR45, FR46, FR47, FR48, FR49, FR50, FR51, FR52
- **Implementation notes:** Depends on Epic 1, Epic 3 (scheduling reminders), Epic 5 (FR51 creates tasks), Epic 10 (FR50 summary), Epic 8 (FR52, optional). The largest single epic by FR count — consider splitting into "live session mechanics" (FR43–46) and "post-session processing" (FR47–52) stories within the epic if a single dev agent's context gets strained.

### Story 12.1: Video session scheduling

As an authenticated user,
I want to schedule a coaching or journaling session,
So that participants and reminders have a shared appointment.

**Traceability:** FR43; NFR3, NFR5, NFR12–NFR16, NFR19, NFR21–NFR28; ADR-005, ADR-008, ADR-018.

**Acceptance Criteria:**

**Given** valid participants, time, timezone, and session policy
**When** a session is scheduled or changed
**Then** the service persists a versioned session, enforces authorization, and emits one reminder request when configured.

**Given** invalid time, unauthorized participant, duplicate command, or conflicting session policy
**When** scheduling is attempted
**Then** the service rejects it without creating a partial session.

### Story 12.2: Join a live WebRTC room

As an authorized participant,
I want to join the live session room,
So that I can communicate in real time.

**Traceability:** FR44; NFR1–NFR4, NFR9–NFR16, NFR19–NFR23, NFR26–NFR28; ADR-012, ADR-018.

**Acceptance Criteria:**

**Given** a scheduled, joinable session and valid participant token
**When** the participant requests room credentials
**Then** the service returns time-limited, room-scoped credentials and never exposes another session's room.

**Given** an expired session, revoked participant, or SFU outage
**When** joining is attempted
**Then** access is denied or degraded safely with bounded timeout and observable failure state.

### Story 12.3: Session timer and warning

As a participant,
I want a shared session timer and end warning,
So that I can manage the session's remaining time.

**Traceability:** FR45; NFR3, NFR10–NFR16, NFR21–NFR27; ADR-012, ADR-018.

**Acceptance Criteria:**

**Given** a session with a configured duration and warning threshold
**When** participants observe the session
**Then** the displayed remaining time is derived from server time and remains consistent across clients.

**Given** client clock drift or reconnect
**When** the timer is refreshed
**Then** the client resynchronizes from the authoritative session state without extending the session.

### Story 12.4: Automatic session end

As a session owner,
I want sessions to end automatically at their policy boundary,
So that rooms and recording resources are not left open indefinitely.

**Traceability:** FR46; NFR1–NFR6, NFR10–NFR12, NFR20–NFR28; ADR-012, ADR-016, ADR-017.

**Acceptance Criteria:**

**Given** a session reaches its end time or is explicitly ended
**When** the end transition commits
**Then** room access is revoked, participants are notified, recording finalization is triggered once, and the state transition is idempotent.

**Given** the end worker retries or a dependency is unavailable
**When** cleanup resumes
**Then** it converges without extending access or leaking media resources.

### Story 12.5: Session recording

As an authorized participant,
I want a session recording created when recording is enabled,
So that I can review the conversation later.

**Traceability:** FR47; NFR3, NFR5, NFR10–NFR16, NFR19, NFR21–NFR28; ADR-012, ADR-018.

**Acceptance Criteria:**

**Given** explicit recording consent and a joinable room
**When** recording starts and ends
**Then** media segments are stored through an access-controlled object reference with participant/session metadata and no public URL.

**Given** consent is absent or storage fails
**When** recording is requested
**Then** recording does not start or is clearly marked incomplete, with no silent capture.

### Story 12.6: HLS playback conversion

As an authorized user,
I want completed recordings converted to HLS,
So that I can play them back efficiently across clients.

**Traceability:** FR48; NFR1–NFR8, NFR10–NFR16, NFR20–NFR28; ADR-012, ADR-016–ADR-018.

**Acceptance Criteria:**

**Given** a finalized recording and valid media metadata
**When** conversion runs
**Then** ffmpeg produces validated HLS manifests/segments, stores them privately, and publishes one completion event.

**Given** corrupt media, conversion timeout, or retry
**When** processing runs
**Then** work is bounded, retryable/idempotent, and permanently failed jobs enter a dead-letter path with diagnostics.

### Story 12.7: Session audio transcription

As an authorized user,
I want session audio transcribed,
So that I can search and review spoken content.

**Traceability:** FR49; NFR1–NFR8, NFR10–NFR16, NFR19, NFR21–NFR28; ADR-003, ADR-009, ADR-016–ADR-018.

**Acceptance Criteria:**

**Given** an authorized finalized audio source
**When** transcription runs
**Then** the service stores a versioned transcript with timing/language metadata and links it to the session.

**Given** unavailable audio, provider failure, or low-confidence output
**When** processing completes
**Then** the result is marked partial/failed with confidence metadata and no invented text.

### Story 12.8: AI session summary

As an authorized user,
I want a session summary generated from the transcript,
So that decisions and themes are easy to revisit.

**Traceability:** FR50; NFR1–NFR4, NFR10–NFR16, NFR19, NFR21–NFR28; ADR-003, ADR-009, ADR-011, ADR-018.

**Acceptance Criteria:**

**Given** an authorized transcript and summary policy
**When** summarization runs
**Then** the summary links to transcript segments, is labeled generated, and produces an AI audit record.

**Given** a missing transcript or unavailable model provider
**When** summarization is requested
**Then** no unsupported summary is published and the failure is retryable or dead-lettered according to policy.

### Story 12.9: Follow-up tasks from action items

As an authenticated user,
I want confirmed session action items converted into follow-up tasks,
So that commitments do not disappear after the conversation.

**Traceability:** FR51; NFR5–NFR8, NFR19, NFR21–NFR28; ADR-003, ADR-007, ADR-017.

**Acceptance Criteria:**

**Given** a summary with extracted action items and user confirmation
**When** task creation is requested
**Then** the service sends idempotent commands to Task & Goal Service, links created tasks back to the session, and reports per-item outcomes.

**Given** duplicate confirmation, invalid action data, or task-service outage
**When** creation runs
**Then** no duplicate tasks are created and retry/compensation state is visible.

### Story 12.10: Optional session-summary blockchain anchor

As an authenticated user,
I want an optional proof of a session summary's integrity,
So that I can verify it was not altered after creation.

**Traceability:** FR52; NFR3, NFR5–NFR8, NFR19–NFR28; ADR-013, ADR-017.

**Acceptance Criteria:**

**Given** explicit user consent and an immutable summary version
**When** anchoring is requested
**Then** only an approved hash/minimal metadata is sent to Blockchain Trust Ledger and the local summary stores its anchor status.

**Given** consent is absent, summary changes, or ledger failure
**When** anchoring runs
**Then** no private content is anchored and the status is accurately pending/failed rather than falsely confirmed.

### Epic 13: Personal Analytics & Insights Dashboard

Users see a unified dashboard of metrics, trends, and AI-generated recommendations drawn from across the whole platform in one aggregated view.

- **FRs covered:** FR5, FR75, FR76, FR77, FR78, FR79, FR80
- **Implementation notes:** Depends on Epic 5, Epic 6, Epic 7 (data sources) and Epic 10 (FR79). First real consumer of the GraphQL aggregation gateway (ADR-006) — FR5 belongs here rather than Epic 2 because a GraphQL aggregation layer has nothing to aggregate until data-producing epics exist.

### Story 13.1: GraphQL dashboard aggregation and core metrics

As an authenticated user,
I want one dashboard query for my core metrics,
So that clients do not coordinate multiple backend calls themselves.

**Traceability:** FR5, FR75; NFR1–NFR4, NFR9–NFR16, NFR19–NFR23, NFR26–NFR28; ADR-003, ADR-004, ADR-006, ADR-007, ADR-018.

**Acceptance Criteria:**

**Given** authorized task, calendar, and finance data sources
**When** the client executes the versioned GraphQL dashboard query
**Then** the gateway resolves a bounded aggregate through internal contracts, preserves subject scope, and returns partial-source status when a non-critical source is unavailable.

**Given** an invalid query, unauthorized field, or slow downstream source
**When** aggregation runs
**Then** depth/complexity limits, timeouts, and bulkheads protect the gateway and the response identifies errors without leaking source details.

### Story 13.2: Habit trends

As an authenticated user,
I want habit trends over time,
So that I can recognize consistency and gaps.

**Traceability:** FR76; NFR1–NFR4, NFR10–NFR16, NFR19, NFR21–NFR28.

**Acceptance Criteria:**

**Given** authorized habit events and a bounded period
**When** trends are requested
**Then** the service returns deterministic aggregates with timezone, missing-data, and calculation metadata.

**Given** incomplete source events
**When** trends are calculated
**Then** gaps are labeled rather than interpreted as failures or filled with invented values.

### Story 13.3: Finance trends

As an authenticated user,
I want finance trends alongside my budget context,
So that I can see whether behavior matches my plan.

**Traceability:** FR77; NFR1–NFR4, NFR10–NFR16, NFR19, NFR21–NFR28; ADR-008.

**Acceptance Criteria:**

**Given** authorized categorized transactions and budgets
**When** finance trends are requested
**Then** totals, comparisons, and budget variance use a documented currency/period policy and exclude unauthorized data.

**Given** source or currency data is incomplete
**When** trends are calculated
**Then** the result is marked partial with source limitations.

### Story 13.4: Productivity insights

As an authenticated user,
I want productivity insights from tasks, goals, calendar, and habits,
So that I can identify bottlenecks in my routines.

**Traceability:** FR78; NFR1–NFR4, NFR10–NFR16, NFR19, NFR21–NFR28; ADR-003, ADR-004, ADR-006, ADR-007.

**Acceptance Criteria:**

**Given** authorized activity data and a selected period
**When** insights are requested
**Then** the service returns explainable metrics with source ids, calculation windows, and no causal claim beyond the measured data.

**Given** a source dependency is unavailable
**When** insights run
**Then** the service degrades to available signals and clearly identifies missing inputs.

### Story 13.5: Analytics-based AI recommendations

As an authenticated user,
I want recommendations based on my analytics,
So that I can choose a focused improvement.

**Traceability:** FR79; NFR1–NFR4, NFR10–NFR16, NFR19, NFR21–NFR28; ADR-003, ADR-004, ADR-006, ADR-011, ADR-018.

**Acceptance Criteria:**

**Given** authorized analytics and personalization consent
**When** recommendations are generated
**Then** each recommendation identifies the signal and time window behind it, is labeled generated, and is recorded in the AI audit trail.

**Given** insufficient data or disabled personalization
**When** recommendations are requested
**Then** the service returns no unsupported recommendation and explains the limitation.

### Story 13.6: Near-real-time analytics processing

As a platform owner,
I want domain events processed into analytics with bounded lag,
So that dashboard data becomes useful without repeatedly scanning operational tables.

**Traceability:** FR80; NFR1–NFR16, NFR20–NFR28; ADR-003, ADR-004, ADR-016–ADR-018.

**Acceptance Criteria:**

**Given** a versioned domain event with trace context
**When** the analytics consumer processes it
**Then** the update is idempotent, observable by lag/error metrics, and correlated back to the originating request.

**Given** a poison event, consumer restart, or backlog above the limit
**When** processing continues
**Then** backpressure, retry, and dead-letter handling preserve the rest of the stream without unbounded memory growth.

### Epic 14: Web Dashboard Client

Users access LifeOS through a web dashboard.

- **FRs covered:** FR81
- **Implementation notes:** Requires approval of the baseline UX contract (visual identity, interaction patterns, mockups) before implementation — see "UX Design Requirements" above. Depends on whichever backend epics the initial dashboard scope surfaces (at minimum Epic 1, Epic 5, Epic 13).

### Story 14.1: Web UX contract and Angular application shell

As a web user,
I want a coherent, accessible Angular application shell,
So that LifeOS feels like one product rather than a collection of APIs.

**Traceability:** FR81; UX prerequisite; NFR23, NFR26–NFR27; ADR-006.

**Acceptance Criteria:**

**Given** the approved UX `DESIGN.md` and `EXPERIENCE.md` contract
**When** the Angular shell is created
**Then** routes, navigation, tokens, responsive breakpoints, loading/error/empty states, and accessibility primitives match the contract.

**Given** no approved UX contract
**When** implementation is scheduled
**Then** the story remains blocked for UX sign-off rather than inventing visual behavior in code.

### Story 14.2: Web authentication and account flows

As a web user,
I want to register, log in, and manage my session from the web app,
So that I can enter and leave LifeOS safely.

**Traceability:** FR81, FR7–FR12; NFR17–NFR26; ADR-020.

**Acceptance Criteria:**

**Given** the identity API contract and approved UX flow
**When** the user registers, logs in, completes OIDC/passkey authentication, or signs out
**Then** the client handles loading, success, error, and recovery states without storing secrets in JavaScript-accessible storage.

**Given** an expired session or denied authorization
**When** a protected route is opened
**Then** the client preserves unsent safe state, redirects through the approved recovery flow, and does not render protected data.

### Story 14.3: Web personal dashboard

As a web user,
I want to view metrics, trends, and prioritized work in one dashboard,
So that I can decide what matters next.

**Traceability:** FR81, FR75–FR79; NFR3, NFR10–NFR16, NFR19, NFR23, NFR26–NFR28; ADR-006.

**Acceptance Criteria:**

**Given** an authenticated user and dashboard query
**When** the dashboard loads
**Then** it renders accessible metric, trend, and recommendation components with source timestamps and partial-data states.

**Given** a slow or failed data source
**When** the dashboard renders
**Then** unaffected sections remain usable, the failure is actionable, and the client does not show stale data as current without labeling it.

### Story 14.4: Web planning workflows

As a web user,
I want to manage tasks, goals, habits, routines, calendar events, and time blocks,
So that dashboard insights connect to an actionable plan.

**Traceability:** FR81, FR18–FR30; UX-DR3–UX-DR6; NFR3, NFR5, NFR19, NFR21–NFR27.

**Acceptance Criteria:**

**Given** an authorized record and a validated form
**When** the user creates, updates, completes, or schedules work
**Then** the UI communicates optimistic/pending/success/failure states and refreshes from server truth after mutation.

**Given** validation, authorization, or network failure
**When** a mutation fails
**Then** the UI preserves user-entered safe values, identifies the field/action needing attention, and never implies a write succeeded.

### Story 14.5: Web finance workflows

As a web user,
I want to manage budgets, transactions, categories, and financial goals,
So that I can turn finance insights into controlled decisions.

**Traceability:** FR81, FR31–FR36; UX-DR3–UX-DR5; NFR3, NFR5, NFR19, NFR21–NFR27; ADR-008.

**Acceptance Criteria:**

**Given** an authorized financial record and validated currency/amount fields
**When** the user creates, categorizes, updates, or reviews it
**Then** the UI shows source period, currency, pending/success/failure state, and server-confirmed totals without false precision.

**Given** validation, authorization, network, or stale-data failure
**When** a financial mutation or read fails
**Then** the UI preserves safe input, identifies the limitation, and never implies a financial write succeeded.

### Epic 15: Desktop Client

Users access LifeOS through a native desktop application.

- **FRs covered:** FR82
- **Implementation notes:** Same UX-design prerequisite as Epic 14. JavaFX + GraalVM Native Image (ADR-014).

### Story 15.1: JavaFX desktop shell

As a desktop user,
I want a native JavaFX LifeOS shell,
So that I can use the platform in a focused desktop workspace.

**Traceability:** FR82; UX prerequisite; NFR23, NFR26–NFR28; ADR-001, ADR-014.

**Acceptance Criteria:**

**Given** the approved UX contract
**When** the desktop shell is launched
**Then** navigation, window states, keyboard interactions, accessibility labels, and failure states follow the shared product contract.

**Given** the service API is unavailable
**When** the shell starts or resumes
**Then** it shows a bounded offline/degraded state and never blocks the UI thread indefinitely.

### Story 15.2: Desktop authentication and secure session storage

As a desktop user,
I want to authenticate and resume a secure session,
So that the native client can access my authorized data.

**Traceability:** FR82, FR7–FR12; NFR17–NFR26; ADR-014, ADR-020.

**Acceptance Criteria:**

**Given** a supported identity flow
**When** the user authenticates
**Then** the client stores refresh/session material only in the platform secure store and sends access tokens over TLS.

**Given** logout, revocation, expiry, or secure-store failure
**When** the session state changes
**Then** protected data is cleared or hidden and the user receives a recoverable error.

### Story 15.3: Desktop planning workspace

As a desktop user,
I want tasks, goals, calendar, and dashboard insights in a multi-pane workspace,
So that I can plan deeply with a large screen.

**Traceability:** FR82, FR18–FR30, FR75–FR79; NFR3, NFR10–NFR16, NFR19, NFR23, NFR26–NFR28; ADR-006, ADR-014.

**Acceptance Criteria:**

**Given** authorized data and a responsive desktop window
**When** the workspace loads or a record changes
**Then** panes remain consistent, server truth wins after mutations, and stale/partial data is labeled.

**Given** a pane dependency fails
**When** the workspace renders
**Then** unrelated panes remain usable and the failure includes retry and diagnostic context.

### Story 15.4: Native packaging and update readiness

As a desktop maintainer,
I want a reproducible native-image package,
So that users can install and run the client predictably.

**Traceability:** FR82; NFR27–NFR28; ADR-001, ADR-014, ADR-019.

**Acceptance Criteria:**

**Given** a supported Java 25/GraalVM build environment
**When** packaging runs
**Then** the artifact is reproducible, signed/verified according to release policy, and includes documented runtime configuration.

**Given** a missing native reflection/resource declaration
**When** smoke tests run
**Then** the build fails before release with a diagnostic rather than discovering the issue after installation.

### Epic 16: Mobile Clients

Users access LifeOS through native iOS and Android apps.

- **FRs covered:** FR83
- **Implementation notes:** Same UX-design prerequisite as Epic 14. Flutter (ADR-015), sharing REST/GraphQL contracts with the other clients.

### Story 16.1: Flutter cross-platform shell

As a mobile user,
I want one Flutter client for iOS and Android,
So that LifeOS behaves consistently across my devices.

**Traceability:** FR83; UX prerequisite; NFR23, NFR26–NFR28; ADR-006, ADR-015.

**Acceptance Criteria:**

**Given** the approved UX contract and shared API schema
**When** the Flutter application starts
**Then** navigation, tokens, localization hooks, responsive behavior, and platform-specific conventions match the contract on both target platforms.

**Given** a small screen, rotation, keyboard, or accessibility setting
**When** a key screen is used
**Then** content remains reachable, readable, and operable without relying on color alone.

### Story 16.2: Mobile authentication and biometrics

As a mobile user,
I want to authenticate with supported identity and device biometrics,
So that returning to LifeOS is secure and convenient.

**Traceability:** FR83, FR7–FR12; NFR17–NFR26; ADR-015, ADR-020.

**Acceptance Criteria:**

**Given** a supported login or passkey flow
**When** the user authenticates
**Then** refresh/session material is stored only in iOS/Android secure storage and access tokens are never written to logs.

**Given** biometric failure, device compromise signal, logout, or token revocation
**When** the client resumes
**Then** it requires the approved recovery flow and hides protected data until authorization is restored.

### Story 16.3: Mobile dashboard and core actions

As a mobile user,
I want a focused dashboard for metrics and next actions,
So that I can manage LifeOS during the day.

**Traceability:** FR83, FR18–FR36, FR75–FR79; NFR3, NFR10–NFR16, NFR19, NFR23, NFR26–NFR28; ADR-006, ADR-015.

**Acceptance Criteria:**

**Given** authorized dashboard data
**When** the mobile dashboard loads
**Then** it shows prioritized content, source timestamps, loading/empty/error states, and accessible actions within the mobile UX contract.

**Given** an offline or intermittent connection
**When** a read or mutation occurs
**Then** the client clearly distinguishes cached, pending, succeeded, and failed states and never silently drops a user action.

### Story 16.4: Mobile notifications and offline recovery

As a mobile user,
I want reminders and recoverable offline behavior,
So that mobile interruptions do not erase my plan.

**Traceability:** FR83, FR70, FR72; NFR2, NFR3, NFR5, NFR6, NFR9–NFR12, NFR23, NFR26–NFR28; ADR-010, ADR-015–ADR-017.

**Acceptance Criteria:**

**Given** notification consent and a registered device
**When** an eligible reminder is delivered
**Then** the payload respects privacy settings and opens the correct authorized destination.

**Given** a queued offline mutation or notification failure
**When** connectivity returns
**Then** the client reconciles using idempotency keys, reports conflicts, and does not duplicate a server-side write.

### Epic 17: Engineering Labs

As an engineer using this project for FAANG-style interview preparation, a dedicated playground exists to practice and demonstrate algorithms, concurrency patterns, distributed-systems patterns, performance engineering, blockchain fundamentals, AI engineering, and system design — each lab is a standalone learning/demonstration deliverable, not a dependency of the product epics.

- **FRs covered:** FR84, FR85, FR86, FR87, FR88, FR89, FR90
- **Implementation notes:** No hard dependency on other epics — can run in parallel with product epics at any time — but each lab is most valuable once it can reference a real product use case from an existing epic (e.g., the Blockchain Lab after Epic 8, the AI Lab after Epic 10), so sequencing it late is a deliberate choice, not a requirement.

### Story 17.1: Algorithms Lab

As an engineer,
I want a broad Algorithms Lab linked to real LifeOS use cases,
So that I can practice correctness and complexity on realistic problems.

**Traceability:** FR84; NFR27–NFR28; ADR-001.

**Acceptance Criteria:**

**Given** each required data structure/algorithm family
**When** the lab is reviewed
**Then** it contains runnable examples, deterministic tests, documented complexity, edge cases, and a mapped product use case.

**Given** an algorithm benchmark or correctness claim
**When** CI validates the lab
**Then** code, tests, and documented claims remain consistent.

### Story 17.2: Concurrency Lab

As an engineer,
I want concurrency examples comparing Java execution models,
So that I can reason about cancellation, scheduling, and throughput.

**Traceability:** FR85; NFR1–NFR4, NFR11–NFR16, NFR27–NFR28; ADR-001–ADR-004.

**Acceptance Criteria:**

**Given** platform threads, virtual threads, executors, futures, structured concurrency, and scoped values
**When** the lab examples run
**Then** they demonstrate cancellation, timeout, context propagation, thread-dump/JFR inspection, and bounded resource behavior.

**Given** a load comparison
**When** it is published
**Then** the methodology and environment are recorded and no unsupported performance number is claimed.

### Story 17.3: Distributed Systems Lab

As an engineer,
I want runnable distributed-systems patterns,
So that I can study failure handling and consistency tradeoffs.

**Traceability:** FR86; NFR1–NFR16, NFR20–NFR28; ADR-005, ADR-007, ADR-010, ADR-016, ADR-017.

**Acceptance Criteria:**

**Given** each named pattern
**When** its example is run
**Then** it demonstrates the happy path, failure injection, recovery behavior, metrics, and documented tradeoffs.

**Given** a retry, duplicate, partition, or backlog scenario
**When** the example is exercised
**Then** resource bounds, idempotency, backpressure, and dead-letter behavior are observable.

### Story 17.4: Performance Lab

As an engineer,
I want repeatable performance experiments,
So that I can identify bottlenecks with evidence.

**Traceability:** FR87; NFR27–NFR28; ADR-001, ADR-002, ADR-006, ADR-007, ADR-018.

**Acceptance Criteria:**

**Given** the required k6, JVM, GC, JFR, query, cache, REST/gRPC, GraphQL, and virtual-thread experiments
**When** an experiment runs
**Then** the output includes workload, environment, methodology, correctness checks, and reproducible result artifacts.

**Given** a result is used in documentation
**When** it is reviewed
**Then** its limitations and confidence are stated and invented numbers are prohibited.

### Story 17.5: Blockchain Lab

As an engineer,
I want a local blockchain learning environment,
So that I can understand proofs, contracts, indexing, and consensus behavior.

**Traceability:** FR88; NFR1–NFR8, NFR11–NFR16, NFR20–NFR28; ADR-013, ADR-018.

**Acceptance Criteria:**

**Given** a local Besu network and Web3j client
**When** the lab examples run
**Then** Merkle proofs, document hashes, contract calls, transaction indexing, Bloom-filter lookup, credential verification, and consensus simulation produce verifiable outputs.

**Given** a node failure or invalid transaction
**When** the scenario is exercised
**Then** the lab documents confirmation, retry, reorg/consistency, and failure behavior without putting private data on-chain.

### Story 17.6: AI Lab

As an engineer,
I want an AI engineering lab,
So that I can evaluate retrieval, tools, providers, and auditability systematically.

**Traceability:** FR89; NFR1–NFR4, NFR10–NFR16, NFR19, NFR21–NFR28; ADR-003, ADR-009, ADR-011, ADR-018.

**Acceptance Criteria:**

**Given** prompt templates, embeddings, vector search, tool calls, local/cloud providers, and evaluation fixtures
**When** the lab runs
**Then** it records deterministic fixture results, cost/latency metadata, safety outcomes, and provider-specific limitations.

**Given** irrelevant retrieval, tool denial, provider outage, or unsafe output
**When** evaluation runs
**Then** the lab demonstrates abstention/degradation and records an auditable result.

### Story 17.7: System Design Lab foundations and URL/notification systems

As an engineer,
I want a reusable system-design documentation template plus URL shortener and notification-system examples,
So that I can practice architecture reasoning from requirements through operations.

**Traceability:** FR90; NFR1–NFR16, NFR20–NFR28; ADR-005, ADR-010, ADR-016–ADR-018.

**Acceptance Criteria:**

**Given** the two named mini-systems
**When** their documentation is reviewed
**Then** each includes requirements, APIs, data model, scaling strategy, bottlenecks, tradeoffs, failure handling, and monitoring.

**Given** a representative simulation
**When** it is exercised
**Then** the documented rate-limit, delivery, retry, or failure invariant is observable and testable.

### Story 17.8: Search engine and distributed scheduler systems

As an engineer,
I want search and scheduling system-design examples,
So that I can reason about indexing, ordering, time, and distributed coordination.

**Traceability:** FR90; NFR1–NFR16, NFR20–NFR28; ADR-005, ADR-007, ADR-010, ADR-016–ADR-018.

**Acceptance Criteria:**

**Given** the search engine and distributed scheduler designs
**When** they are reviewed and simulated
**Then** APIs, data models, partitioning/order guarantees, backpressure, failure handling, and monitoring match the stated requirements.

### Story 17.9: Recommendation engine and rate limiter systems

As an engineer,
I want recommendation and rate-limiting system-design examples,
So that I can compare personalization, fairness, and resource-protection tradeoffs.

**Traceability:** FR90; NFR1–NFR16, NFR20–NFR28; ADR-003, ADR-010, ADR-011, ADR-018.

**Acceptance Criteria:**

**Given** the recommendation engine and rate limiter designs
**When** representative workloads run
**Then** the examples expose latency, cache/limit behavior, degradation, fairness, and dependency failure according to the documented invariants.

### Story 17.10: Chat/messaging and video-session systems

As an engineer,
I want chat and video-session system-design examples,
So that I can reason about real-time delivery, media state, and resource cleanup.

**Traceability:** FR90; NFR1–NFR16, NFR20–NFR28; ADR-012, ADR-016–ADR-018.

**Acceptance Criteria:**

**Given** the chat/messaging and video-session designs
**When** failure and reconnect scenarios are exercised
**Then** ordering, presence/session state, backpressure, cleanup, observability, and recovery behavior are demonstrated.

### Story 17.11: Document-storage and event-analytics systems

As an engineer,
I want document-storage and event-analytics system-design examples,
So that I can practice privacy, indexing, retention, and streaming tradeoffs.

**Traceability:** FR90; NFR1–NFR16, NFR20–NFR28; ADR-008–ADR-011, ADR-016–ADR-018.

**Acceptance Criteria:**

**Given** the document-storage and event-analytics designs
**When** their APIs, data models, retention, failure paths, and monitoring are reviewed
**Then** private data boundaries, idempotency, lag/backpressure, partitioning, and operational signals are explicit.

**Given** a representative ingestion or retrieval simulation
**When** a dependency fails or the backlog grows
**Then** the system demonstrates bounded degradation and recovery without silent data loss.

### Epic 18: Interview & Portfolio Documentation

As an engineer using this project for FAANG-style interview preparation, every major technology choice has a documented why/alternatives/tradeoffs/failure-mode explanation ready to use in an interview.

- **FRs covered:** FR91
- **Status:** Done — 20 documents exist under `docs/interview/`.
- **Implementation notes:** Ongoing/maintenance epic — revisit whenever a new ADR is added (per CLAUDE.md's ADR policy) to keep interview docs in sync with real decisions.

### Story 18.1: Technology-choice interview documentation [DONE]

As an engineer preparing for senior-level interviews,
I want each major technology choice explained in interview-ready language,
So that the repository demonstrates judgment rather than a list of tools.

**Traceability:** FR91; NFR27; ADR-001–ADR-020.

**Acceptance Criteria:**

**Given** each major architecture decision
**When** its interview document is reviewed
**Then** it explains why, alternatives, tradeoffs, wrong-use conditions, bottlenecks, failure behavior, monitoring, and improvement paths.

**Given** a new ADR is added
**When** documentation maintenance runs
**Then** the index/coverage check identifies whether a corresponding interview explanation is required.

### Story 18.2: Planning and delivery documentation quality gates

As a maintainer,
I want requirements, stories, tests, and delivery checks kept traceable,
So that future implementation can be reviewed and released safely.

**Traceability:** NFR27–NFR42; ADR-019; `docs/PROJECT_MANAGEMENT.md`.

**Acceptance Criteria:**

**Given** a change to code, stories, ADRs, or requirements
**When** CI and documentation checks run
**Then** formatting, compilation, unit/integration/contract/security/static/mutation/architecture checks, Docker/SBOM/container checks, staging/smoke checks, and test-report publication are represented by named pipeline stages or explicitly marked as pending infrastructure.

**Given** an FR or story is added, split, or completed
**When** project-management validation runs
**Then** `docs/epics.md`, the corresponding GitHub issue, and roadmap tracking conventions remain aligned without silently renumbering identifiers.
