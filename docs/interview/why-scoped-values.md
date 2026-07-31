# Why Scoped Values Instead of ThreadLocal?

Straight up front: this isn't wired into any running code yet. Both services I have today — identity-service and task-goal-service — are unauthenticated. There's no logged-in user, no tenant ID, no correlation ID being threaded through anything, so there's genuinely no request-scoped context to propagate right now. What I can talk about is the decision I already made for when that need shows up, and why I picked scoped values over the traditional `ThreadLocal` approach before I had a line of code forcing the issue.

The reasoning starts with where this platform is going. Every inbound request — REST today, GraphQL and gRPC later — is eventually going to need ambient context available deep in the call stack: authenticated user, tenant ID, correlation ID, AI session context. The classic answer is `ThreadLocal`, and Spring itself leans on it (`SecurityContextHolder`, `RequestContextHolder`). But I'm building on Java 25 with virtual threads on (`spring.threads.virtual.enabled=true` is already set in both services), and the moment a service fans out concurrent work — like a future dashboard endpoint hitting profile, calendar, and finance services at once via structured concurrency — `ThreadLocal` gets dangerous. It's mutable, unscoped, and there's no compiler-enforced cleanup. On a small pool of reused carrier threads backing millions of cheap virtual threads, a value that isn't explicitly removed can leak across requests. That's a real cross-tenant data leak risk, not a hypothetical.

Scoped values fix that structurally instead of by discipline. A binding is immutable for its dynamic scope, established with `ScopedValue.where(...).run(...)`, and it's simply gone when that block exits — no `finally { threadLocal.remove() }` to forget. More importantly, scoped values are designed to compose with `StructuredTaskScope`: a value bound in a parent task is automatically visible to forked child subtasks with zero propagation code. That's exactly the fan-out shape I expect to write once a service needs to aggregate calls concurrently.

So I made this call now, ADR-first, precisely because it's a foundational pattern I want baked into the service-scaffolding template from day one rather than retrofitted after ten services already lean on `ThreadLocal`. The tradeoffs — rebind-not-mutate ergonomics, JDK 25+ lock-in, a bridging shim for Spring internals that are still `ThreadLocal`-based — are in the ADR, not glossed over here.

## Relevant ADRs

- [ADR-004](../adr/ADR-004-use-scoped-values.md) — the full decision record, options considered, and validation plan
- [ADR-002](../adr/ADR-002-use-virtual-threads.md) — why virtual threads are on, which is what makes ThreadLocal's leak risk acute
- [ADR-003](../adr/ADR-003-use-structured-concurrency.md) — the fan-out pattern scoped values are designed to compose with
