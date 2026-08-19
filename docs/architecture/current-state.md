# Architecture — Current State

This document describes repository-verified implementation, not a production deployment claim. For the
full target architecture, see `REQUIREMENTS.md`'s "High-Level Architecture" and "Core
Microservices" sections. For a diagram, see
[`docs/diagrams/current-architecture.md`](../diagrams/current-architecture.md).

## Implemented service boundaries

A Gradle multi-module monorepo (`settings.gradle.kts`) uses a Java 25 toolchain and independently
packageable Spring Boot 3.5.16 modules. Current service code and its module-local verification
cover:

- **`services/gateway-service`** (8080; management 9080) — bounded public REST/GraphQL ingress with
  authenticated route prefixes, Redis-backed budgets, bounded upstream bulkheads/circuit breakers,
  safe read retries, bounded dashboard fan-out, and correlation propagation. It currently routes Identity, Task/Goal, Profile,
  Calendar, Finance, Document Vault, Media, AI Assistant, and Notification APIs; see
  [`docs/api/gateway-service.md`](../api/gateway-service.md).
- All Spring Boot services receive the shared `contracts:observability` auto-configuration. It
  injects the active W3C trace context into every managed outbound `RestClient`, while each service
  continues to add its trusted correlation header and workload credentials explicitly.
- **`services/identity-service`** (8081; management 9081) — registration, first-party password
  login, configured OIDC and WebAuthn assertion paths, JWT/JWKS/session validation and revocation,
 plus a fail-closed V2 workload/action/resource authorization boundary. V1 decision responses
 remain available for legacy callers; one-time passkey recovery-code APIs, a privacy-safe recovery
 notification outbox/relay, and bounded web passkey registration/login/recovery UX are implemented,
 while external provider delivery, recovery-device policy, and production mTLS are not complete.
- **`services/task-goal-service`** (8082; management 9082) — owner/tenant-scoped Task and Goal
  lifecycles, durable idempotency recovery, persisted mixed Task/Goal dependencies, bounded
  deterministic execution order, and owner-scoped habits, routines, milestones, and recurring
  materialization in `lifeos_task_goal`.
- **`services/profile-service`** (8083; management 9083) — self-only profile, preferences,
  privacy/AI-consent settings, and explicitly permissioned household memberships in
  `lifeos_profile`, with local scope rechecks and redacted audit outcomes. It also owns an
  explicitly opt-in, AES-256-GCM encrypted MongoDB journal/notes boundary; disabled or unavailable
  MongoDB fails closed rather than writing plaintext or silently using PostgreSQL.
- **`services/notification-service`** (8084; management 9084) — encrypted endpoint enrollment,
  recipient-scoped history/SSE, durable inbox dedupe, independent delivery work, retry/dead-letter
  handling, and a delivery-status outbox in `lifeos_notification`. Email and push need a real
  deployment provider; SSE is implemented.
- **`services/calendar-service`** (8085; management 9085) — event lifecycle/recurrence, personal
  focus blocks, half-open conflict detection, bounded free-focus suggestions, reauthorized
  Task/Goal-linked planning projections with priority/deadline ranking, and a durable privacy-safe
  V2 reminder outbox in `lifeos_calendar`. Broker/provider deployment remains separate work.
- **`services/finance-service`** (8086; management 9086) — personal budgets, immutable integer
  minor-unit postings, correction history, bounded currency-scoped insights, a pure robust forecast,
  and financial goals/contributions in `lifeos_finance`. It has no bank, payment, FX, tax, or
  household-finance integration.
- **`services/trust-ledger-service`** (8087; management 9087) — authenticated, bounded document
  hash proofs, Merkle construction, local path verification, and an opt-in Kafka consumer that
  durably records Document Vault proof commands as `PENDING_EXTERNAL_ANCHOR` in its own
  `lifeos_trust_ledger` database. An explicit owner-scoped anchor endpoint and durable idempotency
  state can submit only a digest through an opt-in Web3j/Besu adapter and report `CONFIRMED` only
  after a receipt; it also accepts an internal, workload-authenticated, digest-only Media
  session-summary command with durable idempotency. Private network, contract, and signing-key
  operations remain deployment work.
- **`services/document-vault-service`** (8088; management 9088) — owner-scoped multipart staging,
  versioned document metadata, bounded deterministic metadata plus plain-text/PDF/DOCX/PPTX/XLSX token
  search, durable idempotency, and an opaque local-development object-store reference in
  `lifeos_document_vault`. It also reserves owner-scoped document proof requests and a transactional
  `com.lifeos.document.proof.requested.v1` outbox with a bounded Kafka relay, durable dead-letter handling,
  and a compensating `FAILED` proof state after publication retries are exhausted;
  a Trust Ledger/Besu worker is still required to anchor or verify anything. It fails closed in
  production until a reviewed object-store adapter is supplied;
  other binary extraction and exhaustive indexing remain unavailable. An opt-in Qdrant projection
  of bounded extracted chunks is present for the AI retrieval boundary; provider-backed summaries,
  model-quality embeddings, and production vector operations remain partial.
- **`services/media-service`** (8089; management 9089) — owner-scoped video asset and scheduled
  session control-plane metadata, bounded source-upload validation, private HLS-read policy, local
  development session permits, durable row-locked expiry, deterministic post-session transcript/
  summary/action-item artifacts, and bounded response timer/end-warning semantics in `lifeos_media`.
  It exposes an explicit owner-scoped, strong-version/idempotent session-summary anchor command
  through Trust Ledger; only a digest crosses that boundary and receipt-confirmed production chain
  deployment remains pending.
  An explicit local-development ffmpeg adapter can asynchronously validate and publish private HLS
  artifacts, while production object storage, worker deployment, signaling/SFU, participant
  invitations, recording, speech transcription, AI provider integration, and actual WebRTC remain
  deliberately unavailable or fail closed.
- **`services/ai-assistant-service`** (8090; management 9090) — owner-scoped conversation
  metadata, bounded safety filtering, deterministic owner-scoped goal/task recommendations,
  aggregate-only deterministic Finance insights, a consent-gated deterministic Profile journal
  digest, confirmed DRAFT_TASK/DRAFT_GOAL execution through a privacy-minimized confirmation ledger,
  and confirmed non-mutating DRAFT_FINANCIAL_NOTE proposals
  and the workload-authenticated Task/Goal idempotency boundary, immutable redacted audit facts, and an opt-in owner-filtered Qdrant grounded-question
  endpoint. OpenAI-compatible generation and Qdrant remain disabled by default until reviewed
  deployments are configured; encrypted bounded MongoDB conversation history is available only with
  explicit opt-in. New audit rows include a deterministic SHA-256 commitment over redacted metadata
  (migration V3) and a transactional hash-only outbox (V4/V5), with an opt-in leased Kafka relay
  and Trust Ledger projection; no external chain anchor is claimed. Media transcript/session integration,
  consent UX, production embedding
  quality, provider-backed explanations, and additional cross-service action tools remain partial.
- **`services/analytics-service`** (8091; management 9091) — privacy-minimized account/tenant
  metric snapshots, bounded daily metric history/trends, and an optional V2 notification
  CloudEvent projection with durable event-ID dedupe in `lifeos_analytics`. Dashboard, trend, and
  insight reads plus the AI recommendation projection require a gateway HMAC subject proof; AI
  recommendations additionally require Profile consent with the `ANALYTICS` context category.
  Broad event coverage, retention policy, and provider-backed recommendation narratives remain
  future work.
- **`cli:lifeos-cli`** — a Java 25 read-only local proof helper. Its bounded `hash <file>` command
  streams up to 64 MiB through SHA-256 and emits only a digest/byte count; it never uploads bytes or
  claims blockchain anchoring.

Stateful services own isolated PostgreSQL databases rather than a shared schema, per
[ADR-008](../adr/ADR-008-use-postgresql-as-system-of-record.md). The listed Spring Boot services
enable virtual threads and expose health/readiness/liveness and Prometheus endpoints; the local
observability stack is a reference path, not an assertion of production monitoring coverage.

## Shared foundations now present

- **Kafka/eventing:** `contracts:event-contracts` supplies versioned CloudEvents payloads.
  Calendar writes `NotificationRequestedV2` to its transactional outbox; Notification consumes V1
  and V2 with durable inbox dedupe, fans out locally, and relays delivery-status events. The local
  Compose `eventing` profile includes a single plaintext Kafka broker and explicit development
  topics. This is a working foundation, not production Kafka: ACLs, TLS/authentication, replication,
  retention operations, provider configuration, and broader producer coverage remain outstanding.
- **gRPC:** `contracts:grpc-contracts` compiles generated v1 Calendar, Finance, Task, Document, and
  Analytics protobuf/gRPC APIs and tests their descriptors. Task/Goal, Calendar, Finance, and
  Analytics expose opt-in, workload-authenticated mTLS metrics hosts; the gateway also has an
  opt-in gRPC dashboard client with bounded deadlines and REST compatibility fallback. Document's
  retrieval host and production certificate rollout remain pending. Analytics also exposes a
  bounded owner-scoped dashboard projection over its gRPC contract. Its optional V2 notification
  consumer atomically commits inbox dedupe with the bounded snapshot update and emits low-cardinality
  processed, duplicate, error, and lag metrics; broader domain event producers and production
  broker operations remain pending.
- **Algorithm engine:** `contracts:algorithm-engine` provides bounded topological ordering,
  interval-conflict detection, priority ranking, product-backed examples, and a repeatable local
  smoke benchmark. Task/Goal delegates execution ordering to it. It is a Java library module, not a
  separately deployed remote algorithm service.
- **Trust:** `contracts:trust-ledger` and the Trust Ledger service implement bounded FR63–64 proof
  primitives. Opt-in Kafka consumers durably project Document Vault proof requests and AI Assistant
  hash-only audit commitments as `PENDING_EXTERNAL_ANCHOR`, without storing bytes, prompt text, or
  completion text. The opt-in Web3j/Besu boundary now exposes owner-scoped receipt status for
  credential verification; broker provisioning, AI audit workers, full anchored credential
  issuance remains future work; owner-scoped document and goal certificate verification now checks
  immutable facts against durable receipt state. Completed-goal certificates also have a bounded
  owner-scoped Task/Goal projection, deterministic digest, durable idempotency, and optional
  receipt-confirmed anchor state; Besu deployment/key controls and certificate verification against
  a deployed chain remain pending; see [ADR-025](../adr/ADR-025-bounded-document-proof-core.md),
  [ADR-032](../adr/ADR-032-trust-ledger-proof-request-projection.md), and
  [ADR-045](../adr/ADR-045-ai-audit-trust-ledger-projection.md).
- **Vector grounding:** Document Vault can project bounded extracted chunks to an opt-in Qdrant
  collection and AI Assistant can query it with owner filtering and explicit insufficient-evidence
  refusal. The Compose `vector` profile is local-only; durable outbox reconciliation, production
  embeddings, tenant context propagation, and provider/consent rollout remain open.

`infrastructure/docker-compose/docker-compose.yml` initializes local PostgreSQL databases and
Redis by default. Its optional `eventing` profile adds Kafka, its optional `vector` profile adds
loopback-only Qdrant for grounded-document development, its optional `mongo` profile adds the
loopback-only unauthenticated development MongoDB for encrypted assistant history and Profile
journals/notes, and its optional `observability` profile
adds an OpenTelemetry Collector, Prometheus, Loki, Tempo, Promtail, Grafana, local alert rules, and
a gateway dashboard. These profiles are deliberately local reference infrastructure, not a
production deployment topology.

## Explicitly not complete

- **GraphQL dashboard aggregation** is implemented at the gateway with bounded REST compatibility
  fan-out and an opt-in versioned gRPC fan-out. The gRPC path is limited to the personal tenant
  until an explicit household tenant selector is authorized; production certificate rollout and
  broader service-mesh migration remain pending.
- **No complete gRPC service mesh or production mTLS rollout** exists. Task/Goal, Calendar, Finance,
  and Analytics hosts are opt-in bounded slices; Document retrieval and deployment certificate
  rotation are still required.
- **No production Kafka/notification provider deployment** is claimed. Calendar-to-Notification
  behavior has module/contract coverage; staging broker/provider replay is still required.
- **No production blockchain network is claimed.** Trust Ledger now has a digest-only, durable
  Web3j/Besu anchor boundary and receipt-confirmed state behind explicit configuration, plus a
  loopback-only single-node Besu development profile and checked-in `AnchorRegistry` source; a
  multi-node private network, reviewed deployment/key controls, and staging evidence remain pending.
- **No production Media workflow** is claimed. The module provides a secure control-plane foundation
  plus an explicit local-development ffmpeg path for validated private HLS artifacts, a bounded
  deterministic post-session transcript/summary/action-item artifact, and a workload-authenticated,
  explicitly confirmed follow-up-task command into Task/Goal; it does not claim production
  object-store workers, WebRTC/SFU, recording, speech transcription, AI provider integration, or a
  deployed signaling provider.
- **No live hosted AI generation or production RAG workflow** is claimed. The assistant exposes a
  bounded audited interaction surface, deterministic recommendations/Finance/journal foundations,
  opt-in owner-filtered Qdrant retrieval, encrypted MongoDB history, confirmed DRAFT_TASK/DRAFT_GOAL
  execution, and non-mutating DRAFT_FINANCIAL_NOTE proposals
  mutation path. It returns safe unavailable/insufficient-evidence results until reviewed provider,
  embedding, consent, managed history, and vector operations are configured; additional actions and
  model-backed quality remain partial.
- **No complete Angular, JavaFX, or Flutter product workflow** is implemented. All three shells now
  have a bounded password registration/login boundary; Flutter stores its bearer in platform secure
  storage, while Angular and JavaFX keep tokens in memory, and Angular/Flutter propagate bearers for
  bounded Analytics reads. All shells expose the same eight destinations through native/responsive
  navigation. Web/desktop secure storage, passkey/OIDC UX, native packaging, offline behavior, and
  Web passkey registration/login/recovery UX is now bounded and memory-only; desktop/mobile
  passkey/OIDC UX, secure storage beyond the mobile keystore, native packaging, offline behavior,
  and full domain workflows remain pending.
- **No production central observability deployment** is claimed. The local stack lacks managed
  secret integration, durable remote retention/backup, alert routing, and production TLS/network
  policy.

## Why this order

The implementation first established isolated data ownership and a fail-closed identity decision
boundary, then built bounded domain modules and a deliberately narrow eventing path. This provides
real service, algorithm, Kafka, Trust, and gRPC-contract foundations while keeping external
providers, production broker operations, blockchain confirmation, and client surfaces explicitly
outside the completed scope. GraphQL is implemented as a bounded gateway surface; its optional
gRPC/mTLS path and personal-only scope are described above.
