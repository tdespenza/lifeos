# Why GraphQL for Dashboard Aggregation?

The gateway now exposes a bounded authenticated GraphQL dashboard query. It fronts the current
domain APIs with explicit per-source availability and freshness; the resolver still uses REST
compatibility adapters while Task/Goal, Calendar, and Finance have opt-in mTLS gRPC metrics hosts.
This is a demonstrable first slice, not a claim that the complete GraphQL-to-gRPC mesh is deployed.

The problem GraphQL is meant to solve only shows up once you have composite, read-heavy views spanning multiple services — the personal dashboard is the clearest example: tasks, calendar, a finance snapshot, AI recommendations, notifications, and blockchain proof status, all on one screen. And once you add the fact that I'm planning three clients — Angular web, JavaFX/GraalVM desktop, Flutter mobile — with genuinely different field needs (mobile shouldn't pull full transaction history just to render a summary card), you get a real over-fetching/under-fetching problem. REST forces a choice: either every client calls five or six endpoints and stitches them together itself, or the backend grows a pile of screen-specific endpoints that duplicate composition logic and drift out of sync with the domain models.

I considered a REST BFF per client, but with three first-class clients that triples the maintenance surface and still doesn't solve field-level over-fetching. I also considered sparse fieldsets on REST (`?include=`), which recovers some of the benefit but has no schema, no introspection, and no compile-time contract — field names live in docs, not in a type system.

GraphQL gives one schema-driven aggregation layer sitting alongside REST (which stays for simple CRUD) and gRPC (for internal service calls). Each client asks for exactly the shape it renders, and the schema gives me codegen for TypeScript and Dart, so a field rename breaks the build instead of breaking silently in production — as long as I actually enforce codegen and have clients consume the generated types rather than hand-rolled ones. That's not an inherent REST-vs-GraphQL difference, to be fair: REST with OpenAPI can give the same compile-time guarantee via generated clients. The real reason I'm picking GraphQL specifically is the aggregation/introspection story, not a contract guarantee REST couldn't also provide with the same tooling discipline. It also happens to be a natural fit for structured-concurrency fan-out: the runnable lab now exercises that Java 25 primitive, while the production dashboard remains a bounded REST compatibility fan-out until gRPC client deadlines and cancellation are wired end to end.

The honest cost side: N+1 resolver risk needs DataLoader batching, query cost needs explicit limits, and caching is genuinely harder than REST's free HTTP-level caching. None of that is free, and I'm deferring it until there's an actual multi-service read to aggregate.

Relevant ADRs: [ADR-006](../adr/ADR-006-use-graphql-for-dashboard-aggregation.md)
