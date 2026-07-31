# ADR-004: Use scoped values instead of ThreadLocal for request-scoped context

## Context

LifeOS runs Spring Boot microservices on Java 25 virtual threads with structured concurrency. Every inbound request (REST/GraphQL/gRPC) needs request-scoped context — authenticated user, tenant ID, correlation ID, request metadata, AI session context, and propagated security context — available to deeply nested code (service layer, repository layer, Kafka producers, AI orchestrator calls, logging/tracing interceptors) without threading it through every method signature.

Historically this problem is solved with `ThreadLocal`, which Spring itself relies on (e.g. `SecurityContextHolder`, `RequestContextHolder`). With virtual threads, request handling shifts from a small pool of long-lived platform threads to potentially millions of cheap, short-lived virtual threads, and business logic increasingly forks work via `StructuredTaskScope` (e.g., fan-out calls to profile, calendar, and finance services to build a dashboard). This changes the correctness and performance profile of thread-bound state enough to warrant a dedicated decision.

## Options Considered

1. **ThreadLocal (including InheritableThreadLocal)** — the current de facto standard, well understood by every engineer and supported by every library. Rejected as the primary mechanism because it is mutable and unscoped: nothing prevents a value from being set and never cleared, which is a direct leak vector when virtual threads are cheaply created per request but carrier threads are reused from a small platform pool. `InheritableThreadLocal` copies context on thread creation but does not compose with `StructuredTaskScope` forking or automatically clean up when subtasks complete, and every `ThreadLocal` field is a manual "remember to call `.remove()`" liability in a `finally` block scattered across the codebase.

2. **Explicit context-object parameter passing** — pass a `RequestContext` (or similar) as an explicit argument through every method call chain. Rejected as the general-purpose mechanism because it pollutes essentially every method signature across ~11 microservices and every internal call in controller → service → repository → client chains, including code that has no logical need to know about tenant/user context (pure algorithm code, data mappers). This is the most explicit and testable option but the ergonomic and maintenance cost at this codebase's scale is disproportionate; we still use explicit parameters at true API/library boundaries where a value is a real function input, not ambient context.

3. **MDC-only (SLF4J Mapped Diagnostic Context)** — rejected as a general solution because MDC solves logging enrichment only. It doesn't give business logic a typed, structured way to read `currentUser()` or `tenantId()`, doesn't participate in authorization checks, and is itself ThreadLocal-backed under the hood, so it inherits the same leak/propagation problems for virtual threads. We continue to use MDC, but only for its intended purpose (log line enrichment), fed from the scoped value at the logging boundary.

4. **ScopedValue (JEP 481/446, Java 25)** — selected. Immutable, dynamically scoped bindings established via `ScopedValue.where(...).run(...)`/`.call(...)`, automatically bound for the lifetime of that call (including forked structured-concurrency subtasks) and automatically unbound on exit — no explicit cleanup required.

## Decision Made

Use `ScopedValue` for all request-scoped, read-mostly context that must be ambiently available to deeply nested and forked code: authenticated user, tenant context, correlation/trace ID, request metadata, AI session context, and propagated security context. Bind these once at the ingress boundary (servlet filter / gRPC interceptor / GraphQL data-fetcher wrapper) and rebind explicitly at any point context must legitimately change (e.g., an AI orchestrator subtask acting on behalf of a different tenant). Continue to use explicit parameters for true business-logic inputs, and MDC for log enrichment only, sourced from the bound scoped values.

## Why

Scoped values are immutable for the dynamic scope in which they're bound — a value cannot be silently mutated by nested code, eliminating a whole class of bugs where a shared mutable `ThreadLocal` is stomped by unrelated logic. Binding is lexically scoped to a `run`/`call` block, so there is no "forgot to call `.remove()`" leak path: when the block exits (normally or via exception), the binding is gone, which matters enormously with virtual threads because a leaked value on a reused carrier thread can otherwise bleed into an unrelated request. Critically, `ScopedValue` is designed to compose with `StructuredTaskScope`: values bound in a parent task are visible to forked child subtasks without manual propagation code, which is exactly the fan-out pattern LifeOS uses for cross-service aggregation and AI orchestration.

## Tradeoffs

- **Rebinding, not mutation.** If a subtask needs a different tenant/user context (e.g., an AI orchestrator step impersonating a system account), the code must call `ScopedValue.where(...).call(...)` again to create a new nested binding — there is no `set()`. This is more verbose than `ThreadLocal.set()` at rebind sites, though rare in practice.
- **JDK version lock-in.** `ScopedValue` graduated as a stable feature in a recent JDK; committing to it ties LifeOS to Java 25+ and rules out any future decision to run a service on an older LTS without a shim layer.
- **Library/framework maturity gap.** Not every third-party library (some Spring internals, some instrumentation agents) is scoped-value-aware yet; those integration points still read `ThreadLocal`-backed state (e.g., `SecurityContextHolder`), so we maintain a thin bridging layer that mirrors the scoped-value security context into `SecurityContextHolder` at the point Spring needs it, rather than a clean single source of truth everywhere.
- **Debuggability of unbound access.** Code that reads a scoped value outside any binding throws `NoSuchElementException` immediately (a feature, not a leak), but this means test code and background jobs that reuse business logic must be deliberate about establishing a binding (e.g., a system/service-account context) before invoking shared logic — a category of setup that ThreadLocal's implicit "null means missing" pattern didn't force us to confront until runtime.

## Consequences

- All ingress adapters (REST filter, gRPC interceptor, GraphQL fetcher wrapper, Kafka consumer message handler) must be updated to bind context via `ScopedValue.where(...).run(...)` at the top of the call, and this becomes a mandatory pattern documented in the service scaffolding template — new services get it via the shared starter, not by hand.
- Background/async work (scheduled jobs, Kafka consumers, retried tasks) must explicitly establish a system-level scoped-value binding since there is no inbound request to inherit from; this is now an explicit code-review checklist item.
- Because binding is compile-time-checked (the `ScopedValue<T>` field and its type are static), context-access bugs (wrong type, forgotten field) surface earlier than with `ThreadLocal`'s loosely typed `get()`/untyped map patterns.
- The security-context bridge to Spring's `SecurityContextHolder` is a piece of intentional technical debt to track and remove as Spring Security's virtual-thread/scoped-value support matures.

## When This Decision Would Be Wrong

If a service needs to *mutate* request context mid-flight and have that mutation visible to already-forked sibling subtasks (not just newly forked children) — for example, a long-lived AI orchestrator session that accumulates conversation state across multiple concurrent tool calls and expects all of them to observe updates to a shared, evolving session object — scoped values' immutability makes them the wrong primitive; that use case calls for an explicit shared, synchronized session object passed by reference, not ambient context. Similarly, if LifeOS ever needs to support a JDK LTS release older than 25 for a specific deployment target (e.g., a regulated client environment frozen on Java 21), scoped values would need to be replaced or shimmed with `ThreadLocal` for that build, and this decision should be revisited per-service rather than assumed platform-wide.

## How We Will Validate It

- **Leak test:** a load test (Gatling or k6 driving the identity/profile services) that runs 100k+ sequential requests with distinct tenant IDs at high concurrency over a fixed, small virtual-thread carrier pool, asserting via an audit log/metric that zero responses ever contain a tenant ID or user ID that doesn't match the request that produced them — this is the direct regression test for the ThreadLocal cross-tenant leak risk this ADR exists to close.
- **Structured concurrency propagation test:** an integration test that forks 3+ subtasks via `StructuredTaskScope` from a bound scoped-value context (simulating the dashboard aggregation fan-out) and asserts each subtask observes the correct, unmutated parent context.
- **Latency/throughput benchmark:** a JMH microbenchmark comparing `ScopedValue.get()` against `ThreadLocal.get()` read latency under virtual-thread load, with a target of no more than 5% p99 overhead versus the ThreadLocal baseline it replaces, tracked in CI as a benchmark regression gate.
- **Ongoing observability:** an OpenTelemetry span attribute and Prometheus counter for "context binding missing" exceptions (`NoSuchElementException` from unbound scoped-value reads) in production, alerting if the rate exceeds a near-zero threshold, since any nonzero steady-state rate indicates an ingress or background-job path that skipped binding setup.
