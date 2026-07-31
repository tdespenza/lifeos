# Why GraphQL for Dashboard Aggregation?

Upfront: there's no GraphQL code in the repo today. Right now I have two services — identity-service (account registration) and task-goal-service (goal CRUD plus a topological-sort endpoint) — both exposed over plain REST, and that's genuinely all a client needs at this stage. What I'm describing here is the plan for once there's an actual dashboard to build, not something I can demo.

The problem GraphQL is meant to solve only shows up once you have composite, read-heavy views spanning multiple services — the personal dashboard is the clearest example: tasks, calendar, a finance snapshot, AI recommendations, notifications, and blockchain proof status, all on one screen. And once you add the fact that I'm planning three clients — Angular web, JavaFX/GraalVM desktop, Flutter mobile — with genuinely different field needs (mobile shouldn't pull full transaction history just to render a summary card), you get a real over-fetching/under-fetching problem. REST forces a choice: either every client calls five or six endpoints and stitches them together itself, or the backend grows a pile of screen-specific endpoints that duplicate composition logic and drift out of sync with the domain models.

I considered a REST BFF per client, but with three first-class clients that triples the maintenance surface and still doesn't solve field-level over-fetching. I also considered sparse fieldsets on REST (`?include=`), which recovers some of the benefit but has no schema, no introspection, and no compile-time contract — field names live in docs, not in a type system.

GraphQL gives one schema-driven aggregation layer sitting alongside REST (which stays for simple CRUD) and gRPC (for internal service calls). Each client asks for exactly the shape it renders, and the schema gives me codegen for TypeScript and Dart, so a field rename breaks the build instead of breaking silently in production. It also happens to be a natural fit for the structured-concurrency fan-out I want to demonstrate — resolving tasks, calendar, and finance concurrently under one scope is a concrete use case for Java 25's structured concurrency, which right now I don't have any real code exercising.

The honest cost side: N+1 resolver risk needs DataLoader batching, query cost needs explicit limits, and caching is genuinely harder than REST's free HTTP-level caching. None of that is free, and I'm deferring it until there's an actual multi-service read to aggregate.

Relevant ADRs: [ADR-006](../adr/ADR-006-use-graphql-for-dashboard-aggregation.md)
