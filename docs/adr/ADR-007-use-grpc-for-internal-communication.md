# ADR-007: Use gRPC for internal service-to-service communication

## Context

LifeOS is decomposed into twelve Spring Boot service modules (identity, profile, task/goal, calendar, finance, document vault, media, AI orchestrator, algorithm engine, blockchain trust ledger, notification, analytics) that must talk to each other synchronously for request/response flows: the AI orchestrator calls the algorithm engine to run scoring/optimization routines, the task service calls the calendar service to resolve scheduling conflicts, and the blockchain service is called to verify a document integrity proof before returning a vault read to a user. External clients (Angular, JavaFX/GraalVM desktop, Flutter mobile) already consume REST for resource CRUD and GraphQL for cross-service aggregation. The open question this ADR resolves is narrower: what protocol should services use to call each other, internally, on the synchronous path — not what the public API surface looks like, and not how async events flow (that's Kafka/Pulsar's job).

## Options Considered

- **Internal REST/JSON over HTTP** — reuses the same stack and tooling as the public API, lowest ramp-up cost. Rejected as the default because JSON schemas are advisory (OpenAPI drifts from implementation), there's no generated client/server stub pairing to catch breaking changes at compile time, and JSON's text-based encoding adds parsing and payload-size overhead on high-fan-out internal calls (e.g., AI orchestrator → algorithm engine, called per user action).
- **Message-queue-only internal communication (Kafka/Pulsar for everything)** — good fit for the async/eventing work these queues already do in LifeOS (notifications, analytics ingestion, outbox pattern), but awkward for genuine synchronous request/response: task-service-needs-an-answer-from-calendar-service-right-now doesn't map cleanly onto pub/sub without building a correlation-ID/reply-topic pattern that re-invents RPC with worse latency and more moving parts.
- **Apache Thrift** — offers comparable binary-RPC characteristics (IDL, compact wire format, codegen) and was seriously considered as a gRPC alternative. Rejected because its ecosystem, tooling, and community momentum are smaller than gRPC's today (weaker Spring integration, fewer maintained client libraries across our target languages, smaller pool of engineers who already know it), which raises onboarding and maintenance cost without a corresponding technical advantage over gRPC for our use case.

## Decision Made

Use gRPC (Protocol Buffers + HTTP/2) for all synchronous internal service-to-service calls, with shared `.proto` contracts versioned in a dedicated `grpc-contracts` module. REST remains the external, resource-oriented API; GraphQL remains the external aggregation layer for clients; Kafka/Pulsar remains the async event backbone. gRPC only replaces internal HTTP/JSON calls between services.

### Transitional exception

The repository now contains opt-in mTLS gRPC metrics hosts for Task/Goal, Calendar, and Finance,
plus a gateway client for their personal dashboard contracts. Those hosts are deployment-owned and
disabled by default until certificates and workload tokens are provisioned; Document/Analytics
transport and a complete service-mesh rollout remain future work. Story 1.6 therefore still uses
one narrow internal REST/JSON bridge between
`task-goal-service` and `identity-service` for durable JWT validation and authorization
decisions. It is explicitly a migration seam, not a new default for internal calls: it uses
versioned request/decision DTOs, authenticated workload identity, bounded client timeouts,
fail-closed dependency behavior, and a transport-independent policy domain. Production deployment
must restrict that route to internal TLS/mTLS (or an equivalent workload-identity control). The
bridge must move to generated `grpc-contracts` stubs when that module is introduced; no additional
internal REST integrations should treat this exception as precedent.

## Why

Proto-defined contracts are compiled, not merely documented — a breaking change to a request/response message fails the build for both caller and callee before it ships, which matters when 11 services are evolving independently. Protobuf's binary encoding plus HTTP/2 multiplexing gives materially lower serialization cost and latency than JSON/HTTP/1.1 on the calls we expect to be hottest (AI orchestrator → algorithm engine can be invoked many times per user interaction). Native streaming (client, server, and bidirectional) is a direct fit for algorithm-engine workloads that may need to stream partial results, and for future document-verification flows that stream proof chains rather than returning a single blob. Codegen also gives us typed clients across our JVM services for free, with a clear extension path if additional runtimes are added later.

## Tradeoffs

- Binary protobuf payloads are not human-readable on the wire, so debugging requires `grpcurl`/reflection tooling rather than reading a browser network tab or curling an endpoint — this is a real cost during incident response.
- Every internal contract change now requires a `.proto` update, codegen, and a coordinated deploy discipline (backward-compatible field changes, reserved field numbers) — more process than "just change the JSON shape and hope."
- HTTP/2-based gRPC does not work out of the box through some corporate proxies, load balancers, or browser clients — this is precisely why gRPC is scoped to internal traffic only and never exposed as a public API.
- Local development and testing add a codegen build step (proto compilation) to each service's build pipeline, increasing build complexity versus plain REST controllers.
- Team members without prior gRPC/protobuf experience face a steeper learning curve than REST, which every engineer already knows.

## Consequences

All internal RPC contracts live in `grpc-contracts/`, becoming a shared dependency and a de facto integration point that every service team must coordinate through — this is deliberate API discipline, but it means the contracts module needs its own ownership and review process to avoid becoming a bottleneck. Services gain typed, generated stubs, eliminating a class of internal integration bugs caused by hand-written JSON DTOs drifting out of sync. Observability must be extended explicitly: OpenTelemetry gRPC interceptors are required on every service (not automatic the way HTTP instrumentation often is) to keep distributed traces continuous across gRPC hops. Deployment topology must support HTTP/2 end-to-end for internal traffic (service mesh or load balancer configuration), which is an explicit infra requirement, not a given.

## When This Decision Would Be Wrong

This choice should be revisited if LifeOS's internal call graph shifts toward being dominated by low-frequency, low-fan-out calls between only two or three services — at that scale the codegen/build overhead and debugging friction of gRPC stop paying for themselves against plain REST. It would also be wrong if the team composition shifts toward contributors who need to read/modify internal contracts without JVM tooling fluency (e.g., a large scripting/ops-heavy contributor base) where protobuf codegen becomes a persistent onboarding tax. Finally, if a future requirement mandates that "internal" services be callable directly from browser-based clients without a gateway translation layer, gRPC-Web's limitations would force a re-evaluation of the boundary between internal and external protocols.

## How We Will Validate It

Before rollout, benchmark the two hottest internal paths — AI orchestrator → algorithm engine and task service → calendar service — under equivalent load using both gRPC and internal REST/JSON implementations of the same operation, measuring p50/p95/p99 latency and CPU time spent in (de)serialization. Target: gRPC p99 latency at least 20% lower and payload size at least 40% smaller than the JSON equivalent for the algorithm-engine scoring call, which is our highest-fan-out internal path. Post-rollout, track per-service gRPC error rates (by status code), p99 latency, and connection/stream counts in Grafana dashboards fed by the OpenTelemetry gRPC interceptors, with an alert threshold if internal gRPC p99 exceeds the equivalent REST baseline recorded in the pre-rollout benchmark — that regression would be a concrete signal to revisit this ADR rather than a vague "keep an eye on it."
