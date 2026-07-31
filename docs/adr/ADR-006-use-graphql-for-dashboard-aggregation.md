# ADR-006: Use GraphQL for dashboard aggregation and client-specific views

## Context

LifeOS exposes eleven backend microservices (identity, profile, task/goal, calendar, finance, document vault, media, AI orchestrator, algorithm engine, blockchain trust ledger, notification, analytics). Several client surfaces — the Angular web app, the JavaFX/GraalVM desktop app, and the Flutter mobile app — need composite, read-heavy views that span multiple services in a single render: the personal dashboard (tasks + calendar + finance snapshot + AI recommendations + notifications + blockchain proof status), the goal overview, the AI recommendation panel, and the cross-service profile summary.

These views do not map to a single service's resource model. A naive REST approach forces clients to either call five to six endpoints per screen and stitch the results themselves, or forces backend teams to hand-roll a growing set of screen-specific REST endpoints that duplicate composition logic and drift from the underlying domain models. Mobile clients on cellular connections are also sensitive to over-fetching (full resource payloads when only a few fields render on screen) and to round-trip count (each additional call adds latency and battery cost). The system already commits to REST for simple resource CRUD (create task, upload document, update profile, create budget category) and gRPC for internal service-to-service calls; this decision concerns only the aggregation/read layer that composite UI views depend on.

## Options Considered

- **GraphQL gateway** — a single schema-driven endpoint that resolves fields across services, letting each client request exactly the shape it needs.
- **Dedicated BFF (Backend-For-Frontend) REST layer per client** — one aggregation service per client platform (web-BFF, mobile-BFF, desktop-BFF), each hand-composing REST responses tailored to that client. Rejected as the primary approach: it triples the maintenance surface (three services to keep in sync with backend schema changes) and still forces a manual, per-endpoint negotiation of what fields to include, which is exactly the over-fetching problem restated at a different layer. It remains a reasonable pattern for a single dominant client, but LifeOS has three first-class clients with divergent field needs.
- **Client-side composition from multiple REST calls** — each client independently calls the task, calendar, finance, AI, notification, and blockchain-status endpoints and merges results locally. Rejected: it pushes orchestration and partial-failure handling into every client codebase (three times), increases round-trip count and tail latency on mobile networks, and has no shared caching or query-shape contract, so any backend field rename becomes a silent, hard-to-detect client bug.
- **REST with sparse fieldsets / `?include=` query params** — extend the existing resource-based REST APIs with field-selection and relationship-expansion parameters (JSON:API style). Rejected as the primary aggregation mechanism: it recovers some of the over-fetching benefit but has no type system, no schema introspection, and no compile-time contract for clients — field names and nesting rules live only in documentation, which weakens discoverability and makes it harder to demonstrate strong API design in review.

## Decision Made

Use a GraphQL gateway as the aggregation layer for dashboard and client-specific composite views, sitting alongside (not replacing) REST for simple resource CRUD and gRPC for internal service-to-service calls. The GraphQL layer resolves fields by fanning out to backend services — internally over gRPC — and composing a single typed response per client query.

## Why

- **Over-fetching/under-fetching control**: each client (Angular, JavaFX desktop, Flutter mobile) requests exactly the fields it renders; the mobile client can omit heavy fields (e.g., full transaction history) that the desktop dashboard includes.
- **One aggregation contract, many shapes**: a single schema replaces N screen-specific REST endpoints; new dashboard widgets are additive schema changes, not new backend routes.
- **Typed, introspectable contract**: the schema is self-documenting and enables client-side codegen (TypeScript types for Angular, Dart types for Flutter), catching field-mismatch errors at build time instead of runtime.
- **Natural fit with structured concurrency**: resolver fan-out (tasks, calendar, finance, AI recommendations, notifications, blockchain proof status) maps directly onto the project's Java 25 structured-concurrency pattern for the dashboard-load use case, giving a concrete, demonstrable example of virtual-thread-backed parallel resolution under one managed scope.
- **Portfolio signal**: correctly-scoped GraphQL (used only where aggregation genuinely helps, not everywhere) demonstrates deliberate API design judgment rather than technology-for-its-own-sake, which is the point being evaluated in a FAANG-style review.

## Tradeoffs

- **N+1 resolver risk**: naive field resolvers (e.g., resolving `goal.owner` per goal) can generate N backend calls; this requires DataLoader-style batching per request, which is additional infrastructure REST endpoints don't need.
- **Query cost is client-controlled**: an unbounded or deeply nested client query can force the gateway to fan out to every backend service at once; this requires explicit query depth/complexity limits and per-field cost budgeting, which is operational work with no REST equivalent.
- **Caching is harder**: REST benefits from HTTP-level caching (CDN, ETags, cache-control) almost for free; GraphQL responses are per-query and require either persisted queries with fixed cache keys or application-level caching (Redis) — REST's simplicity there is genuinely lost here.
- **Two API paradigms to operate**: the team now owns REST conventions and GraphQL schema/resolver conventions, error semantics, and versioning strategy simultaneously, which is real ongoing cost, not a one-time setup cost.
- **Weaker HTTP-status-code semantics**: GraphQL over HTTP typically returns 200 with errors in the payload, which complicates edge/gateway-level monitoring and alerting that assumes REST status-code conventions.

## Consequences

- A GraphQL gateway service (or module within the API gateway) must be built and owned, with its own schema registry, resolver layer, and deployment lifecycle — this is new operational surface, not a config toggle on existing services.
- Resolvers must call backend services over the existing internal gRPC contracts, so GraphQL becomes a second API surface backed by the same gRPC service layer — schema changes in a backend service's gRPC contract must be reflected in the GraphQL schema, requiring coordinated versioning.
- DataLoader batching, query complexity limits, and persisted queries become required infrastructure before the dashboard ships to avoid N+1 fan-out and denial-of-service-by-query-shape.
- Observability must extend to per-field resolver latency and error rate (via OpenTelemetry spans per resolver), not just per-endpoint metrics, since a single slow field can degrade an otherwise-fast aggregate query.
- Client teams (Angular, Flutter, JavaFX) adopt GraphQL codegen tooling for their respective languages, adding a build-time dependency that REST clients did not have.

## When This Decision Would Be Wrong

If LifeOS ever collapses to a single client platform (e.g., web-only, mobile and desktop deprecated), the primary justification — serving divergent field needs to multiple clients from one schema — mostly disappears, and a simpler dedicated REST BFF for that one client would have less operational overhead than running a GraphQL gateway. Similarly, if dashboard-style aggregation queries turn out to be low-volume and low-variability in practice (e.g., only 3-4 fixed dashboard shapes ever get requested, with no ad-hoc client-driven field selection), persisted-query REST endpoints with fixed response shapes would deliver the same caching and payload-size benefits with far less resolver and query-cost-governance machinery. This should be revisited if client count drops to one, or if telemetry shows fewer than a handful of distinct query shapes are ever executed against the gateway over a full quarter.

## How We Will Validate It

- **Payload size**: measure p50/p95 response payload size for the personal dashboard query versus the equivalent REST-composition (sum of the 5-6 REST calls it replaces); target at least 40% reduction in bytes transferred for the mobile client profile.
- **Latency**: benchmark end-to-end dashboard load time (client request to fully-resolved response) under structured-concurrency fan-out; target p95 under 400ms for the aggregate dashboard query against warmed backend services, matching the REQUIREMENTS.md commitment to publish GraphQL aggregation benchmarks.
- **N+1 detection**: add an automated test that asserts, for each production dashboard query, the number of downstream gRPC calls does not exceed a fixed budget (e.g., one call per distinct service touched, not per entity), failing CI if batching regresses.
- **Query cost enforcement**: load-test the gateway with a deliberately expensive nested query (e.g., deeply nested goal → tasks → subtasks → history) and confirm the complexity limiter rejects it before it reaches backend services, rather than degrading them.
- **Client adoption signal**: track the ratio of dashboard/aggregation traffic served by GraphQL versus any remaining ad-hoc REST composition in client code six months post-launch; near-zero residual REST composition for these views confirms the schema actually covers client needs.
