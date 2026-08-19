# Why Scoped Values Instead of ThreadLocal?

Straight up front: scoped values are wired into the running services for one deliberately narrow
purpose: each HTTP ingress filter binds the validated correlation ID for the lifetime of the
request. Gateway and Task/Goal use that bound value on their nested HTTP calls; user and tenant
facts remain explicit parameters at the authorization boundary. There is no structured-concurrency
fan-out yet, so broader ambient request context remains intentionally unimplemented.

The reasoning starts with where this platform is going. Every inbound request — REST today, GraphQL and gRPC later — is eventually going to need ambient context available deep in the call stack: authenticated user, tenant ID, correlation ID, AI session context. The classic answer is `ThreadLocal`, and Spring itself leans on it (`SecurityContextHolder`, `RequestContextHolder`). I want to be precise about *why* that's a worse fit here, though, rather than reach for the first scary-sounding reason: it's not that virtual threads "reuse carrier threads" in a way that leaks `ThreadLocal` state between unrelated requests — they don't. A `ThreadLocal` value belongs to the virtual thread itself, not to whichever carrier thread happens to be running it at a given moment, and virtual threads are never pooled or reused across unrelated tasks the way platform threads in a fixed pool are, so that specific "value bleeds into the next request" failure mode doesn't actually apply here. The real problems are more structural: `ThreadLocal` is mutable and unscoped, so nothing stops arbitrary code deep in the call stack from silently overwriting a value another caller set, which makes data flow hard to reason about; cleanup depends on someone remembering `finally { threadLocal.remove() }`, which is a real bug class on any code path that reuses a thread (a platform-thread executor, for instance) even if virtual threads themselves sidestep it; and, most concretely for LifeOS, `ThreadLocal` doesn't compose with `StructuredTaskScope` — a value bound in a parent doesn't automatically propagate to forked child subtasks the way a scoped value does, which matters the moment a service fans out concurrent work, like a future dashboard endpoint hitting profile, calendar, and finance services at once.

Scoped values fix that structurally instead of by discipline. A binding is immutable for its dynamic scope, established with `ScopedValue.where(...).run(...)`, and it's simply gone when that block exits — no `finally { threadLocal.remove() }` to forget. More importantly, scoped values are designed to compose with `StructuredTaskScope`: a value bound in a parent task is automatically visible to forked child subtasks with zero propagation code. That's exactly the fan-out shape I expect to write once a service needs to aggregate calls concurrently.

The narrow correlation use validates the core lifecycle guarantee before more complex fan-out puts
pressure on the design. The tradeoffs—rebind-not-mutate ergonomics, JDK 25+ lock-in, and a bridging
shim for Spring internals that are still `ThreadLocal`-based—are in the ADR, not glossed over here.

## Relevant ADRs

- [ADR-004](../adr/ADR-004-use-scoped-values.md) — the full decision record, options considered, and validation plan
- [ADR-002](../adr/ADR-002-use-virtual-threads.md) — why virtual threads are on, which is what makes ThreadLocal's leak risk acute
- [ADR-003](../adr/ADR-003-use-structured-concurrency.md) — the fan-out pattern scoped values are designed to compose with
