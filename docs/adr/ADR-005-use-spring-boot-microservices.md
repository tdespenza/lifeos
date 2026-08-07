# ADR-005: Use Spring Boot microservices as the backend architecture

## Context

LifeOS is a personal operating system spanning identity, profile, tasks/goals, calendar, finance, document vault, media streaming, AI orchestration, an algorithm engine, a blockchain trust ledger, notifications, and analytics. These domains differ sharply in data model, consistency requirements, scaling profile, and blast radius: finance and identity need strict transactional integrity and auditability; media streaming and AI orchestration are bursty and compute/GPU-bound; the blockchain trust ledger only needs to anchor hashes and can lag behind the rest of the system without correctness loss. The project also has an explicit secondary goal — demonstrating staff-level distributed-systems judgment (service boundaries, contracts, resilience patterns, independent deployability) for FAANG-style technical interviews and architecture reviews. Any backend architecture decision has to satisfy both the product's operational needs and this demonstration goal.

## Options Considered

- **Java modular monolith** (single deployable, module boundaries enforced via Java modules or package conventions). Rejected as the primary architecture: it would simplify operations and reduce cross-service latency, but it collapses the failure domains that finance, identity, and the blockchain ledger need kept apart, and it does not exercise or demonstrate service discovery, distributed tracing, independent scaling, or circuit-breaker patterns — a stated project goal.
- **Micronaut or Quarkus microservices**. Rejected as the primary framework: both offer faster cold start and lower memory footprint, which matters for serverless-style scaling, but Spring Boot has materially larger ecosystem coverage (Spring Data, Spring Security, Spring Cloud, Kafka/Pulsar bindings, observability starters) and is the framework most recognized in FAANG-adjacent backend interviews, which lowers integration risk and raises the interview-relevance payoff for the same engineering effort.
- **Serverless functions per capability** (e.g., one Lambda/Cloud Function per endpoint or command). Rejected: calendar and finance are long-lived, stateful, connection-heavy domains (open transactions, recurring computation, WebSocket/notification fan-out) that fit poorly with a stateless, short-lived execution model, and this approach would sidestep rather than demonstrate classic microservice patterns like service discovery and circuit breakers that are core to the project's engineering-portfolio purpose.

## Decision Made

Build LifeOS as a set of independently deployable Spring Boot microservices — API Gateway, Identity, Profile, Task/Goal, Calendar, Finance, Document Vault, Media Streaming, AI Orchestrator, Algorithm Engine, Blockchain Trust Ledger, Notification, and Analytics — each owning its own responsibility and, where warranted, its own datastore, communicating over REST/GraphQL/gRPC and Kafka/Pulsar, run on Java 25 virtual threads with structured concurrency.

## Why

The service boundaries in this decision map to real domain boundaries with different consistency, compliance, and scaling needs, not to org-chart convenience. Finance and identity can be isolated behind stricter authz, audit logging, and change-control than, say, notification or analytics. Media streaming and AI orchestration can scale horizontally and independently of the rest of the system during load spikes without over-provisioning low-traffic services like the trust ledger. Kafka/Pulsar between services lets slow or degraded consumers (e.g., analytics, blockchain anchoring) fall behind without blocking user-facing request paths. Java 25 virtual threads make the classic microservices tax — thread-per-request fan-out across many blocking network calls — cheap enough that the operational overhead of "many services" is no longer offset by a throughput penalty the way it was with platform threads. Spring Boot specifically was chosen over Micronaut/Quarkus because the ecosystem breadth (Spring Cloud Gateway, Spring Security, Spring Data across Postgres/Mongo/Redis, Micrometer/OTel starters) reduces the amount of custom plumbing needed across twelve services, and its ubiquity in industry means the resulting system reads as directly relevant experience in a technical interview.

## Tradeoffs

- Distributed transactions across Finance, Task/Goal, and Notification require sagas or outbox patterns instead of a single ACID commit — added implementation and testing complexity in exchange for failure isolation.
- Twelve independently deployable services multiply the operational surface: twelve sets of health checks, CI/CD pipelines, dashboards, and on-call runbooks versus one for a monolith.
- Cross-service calls (e.g., Calendar reading Profile data, AI Orchestrator calling Algorithm Engine) add network latency and partial-failure modes that a monolith's in-process calls never have.
- Schema evolution across service-owned datastores requires versioned contracts (API versioning, event schema registries) rather than a single shared migration history.
- Local development and end-to-end debugging require running or mocking a multi-service topology, which is slower to iterate on than a monolith for a single developer.

## Consequences

- The API Gateway becomes a required component and a single point of coordination for auth, rate limiting, and routing — it must be built and tested to the same reliability bar as the services behind it.
- Each service needs its own resilience posture (timeouts, bounded retries with backoff, circuit breakers) at every synchronous call it makes to another service, per the reliability standards in REQUIREMENTS.md.
- Observability (OpenTelemetry trace propagation, correlation IDs, per-service dashboards) is not optional polish — without it, a request spanning Gateway → Identity → Calendar → Notification is undebuggable in production.
- The Blockchain Trust Ledger and Analytics services can be deployed, scaled, and rolled back independently of Finance and Identity, which is the specific benefit this decision is buying and should be visible in the deployment pipeline and scaling configuration.
- Team velocity for early development is slower than a monolith would allow, since even a single developer must stand up service-to-service contracts before end-to-end features work.

## When This Decision Would Be Wrong

If LifeOS stays a single-maintainer hobby project with no plan to scale traffic or team size beyond one contributor, the operational cost of twelve services (pipelines, dashboards, multi-service local dev) would outweigh the architectural and interview-demonstration benefits, and collapsing low-traffic, tightly-coupled services (e.g., Profile, Notification, Analytics) into a modular monolith or a smaller set of 3-4 coarser services would be the right call. Similarly, if the AI Orchestrator and Algorithm Engine turn out to need synchronous, sub-10ms round trips to Task/Goal data at high call volume, the network hop between them could become a bottleneck that argues for merging those two services rather than preserving the boundary.

## How We Will Validate It

- **Latency budget**: p99 end-to-end latency for a representative cross-service request (Gateway → Identity → Calendar → Notification) stays under 300ms under a load test of 200 requests/second sustained for 10 minutes; if p99 exceeds this, the service boundary or call pattern is reconsidered.
- **Independent deployability**: measure and confirm that a deploy of any one service (e.g., Notification) completes with zero downtime and zero error-rate increase (>0.1% 5xx) in unrelated services (e.g., Finance), verified via a canary deploy in staging before each release.
- **Failure isolation**: chaos-test by killing the Blockchain Trust Ledger service and confirming Finance and Task/Goal continue serving requests with no more than the expected degraded-feature behavior (no ledger confirmation), validating that failure domains are actually isolated as designed.
- **Resource efficiency**: track per-service memory/CPU under Java 25 virtual threads versus a platform-thread baseline for the same load test, confirming the concurrency model offsets the multi-service overhead this ADR assumes.
