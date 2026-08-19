# Why Virtual Threads?

The current Spring Boot modules—gateway-service, identity-service, task-goal-service,
profile-service, notification-service, calendar-service, finance-service, and
trust-ledger-service—run with `spring.threads.virtual.enabled=true`. That is a real implementation
foundation, not a performance result: no product concurrent-I/O fan-out has been load-tested. I
adopted it early because it shapes code and dependency choices before a fleet needs a costly
runtime migration.

The reasoning starts from the target shape of the system, not merely the current modules. LifeOS is
meant to grow into roughly a dozen Spring Boot services that are almost entirely I/O-bound —
Postgres queries, Redis lookups, downstream service calls, LLM calls, blockchain proof submission.
That's the textbook case virtual threads solve: a thread blocked on I/O releases its carrier thread
instead of parking an OS thread — but that is conditional on the actual client/driver in use
supporting unmounting, not automatic just because the call happens to be I/O. Standard `java.net`
blocking sockets get this transparently, and PostgreSQL JDBC uses that kind of blocking socket I/O
for the stateful services. Gateway and Identity use Redis through Spring's Lettuce client; its
synchronous API blocks a virtual thread on a `RedisFuture`, a different mechanism from blocking
socket I/O, so its behavior still needs workload validation rather than an assumption by analogy to
JDBC. That's exactly why [ADR-001](../adr/ADR-001-use-java-25.md)'s "every blocking dependency must
be verified as virtual-thread-safe, not assumed" is stated as a per-dependency rule rather than a
one-time check. The alternative I seriously considered was Spring WebFlux/Project Reactor, which I
rejected because it means rewriting the entire call chain in a non-blocking style, with Mono/Flux
everywhere and stack traces that are much harder to read.

If someone pushes on "but you haven't proven this scales" — that's fair, and I'd say so directly.
The current modules use bounded dependency calls and Kafka relay workers, but no concurrent
dashboard-style fan-out is load-tested, so virtual threads currently buy correctness-neutral
headroom, not a measured win. Where this actually gets tested is a dashboard aggregation endpoint
that calls several services concurrently, which is exactly the workload virtual threads and
structured concurrency (a separate, related decision, and still a preview API — JEP 505 — as of
Java 25) are meant for. I also know the sharp edges going in: as of JEP 491 (Java 24, carried into
25), ordinary `synchronized` blocks and methods no longer pin the carrier thread. Pinning still
happens through native code, foreign-function calls, or class initialization/resolution.
`ThreadLocal`-heavy patterns are a different concern because they do not compose with
`StructuredTaskScope` fan-out, which is why correlation context uses `ScopedValue` while future
user/tenant context remains explicit.

**Relevant ADRs:** [ADR-002](../adr/ADR-002-use-virtual-threads.md)
