# ADR-003: Use structured concurrency for grouped concurrent workflows

## Context

LifeOS has several workflows that fan out to multiple independent sources and then need the combined result: dashboard aggregation (tasks, calendar, finances, AI recommendations, notifications, blockchain proof status), AI recommendation gathering, search fanout, video processing pipelines, blockchain verification, and smart scheduler evaluation. Each of these is a "one logical unit of work, many concurrent subtasks" shape — the caller doesn't care about individual subtask completion, it cares about the group succeeding, partially failing, or being cancelled together.

Java 25 ships `StructuredTaskScope` (JEP 505, the fifth preview iteration, following incubation as JEP 428/437 and preview iterations JEP 453/462/480/499). It ties the lifetime of child tasks to a lexical scope: when the scope block exits — by success, exception, or cancellation — every child thread is guaranteed to be joined or cancelled before control returns. That guarantee is exactly the shape of the problems above, and it fits naturally with the virtual-threads model LifeOS already uses for I/O-bound service calls.

## Options Considered

1. **`StructuredTaskScope` (structured concurrency)** — chosen. Subtasks are forked inside a scope, joined together, and the scope owns cancellation propagation and shutdown-on-failure/shutdown-on-success policies.
2. **Raw `CompletableFuture` composition (`allOf`/`anyOf`)** — the incumbent pattern elsewhere in the JDK ecosystem. Rejected because cancellation does not propagate: cancelling the composed future does not interrupt the underlying work, so a slow finance-service call can keep running on a pooled thread after the dashboard request that triggered it has already timed out or been abandoned by the caller. Composing many `CompletableFuture`s for a group also produces callback-heavy code that is hard to reason about when one branch fails and the others should stop.
3. **Reactive fan-out (Project Reactor `Flux.zip`/`merge`)** — rejected for this use case. Reactor is powerful and has good backpressure primitives, but it introduces a second concurrency model alongside virtual threads, has a steep learning curve for contributors not already fluent in reactive operators, and is materially harder to explain and justify in a design review or interview than a scope with forked tasks and a join call. We are not choosing it as a general-purpose async model for LifeOS, so introducing it only for fan-out would fragment the codebase's concurrency story.
4. **Manual `ExecutorService` + `CountDownLatch`** — rejected as the general pattern. It works, but every callsite has to hand-roll cancellation, exception aggregation, and latch bookkeeping. It's error-prone (forgetting to count down on the failure path is a classic bug class), and it gives none of the structural guarantees against orphaned threads that `StructuredTaskScope` provides by construction.

## Decision Made

Use `StructuredTaskScope` as the standard pattern for grouped concurrent workflows across LifeOS services, paired with virtual threads for the forked subtasks. Use the built-in joiner policies (`ShutdownOnFailure` for all-or-nothing aggregation such as blockchain verification, `ShutdownOnSuccess` for first-result races, and custom joiners where partial-success semantics are needed, e.g. dashboard aggregation where one slow subsystem shouldn't blank out the whole page).

## Why

- **Cancellation is propagated, not merely requested.** When the scope exits (timeout, exception, or explicit close), every forked virtual thread is interrupted and joined before the enclosing method returns. There is no window where a subtask keeps running against a request the caller has already given up on.
- **Error handling composes naturally with try/catch instead of callback chains.** A failed subtask surfaces through the scope's join/throwIfFailed path, which reads like sequential code even though the work is concurrent.
- **It eliminates an entire bug class: orphaned tasks.** Structural scoping (the JDK's "no child thread outlives its parent scope" guarantee) removes the need to manually track and cancel a set of futures — the language/runtime enforces it.
- **It matches the observability model we already need.** A scope is a natural place to attach a single trace span and correlation ID for the whole fan-out, with child spans per subtask, rather than instrumenting each `CompletableFuture` callback independently.
- **It demonstrates current Java expertise.** For a project explicitly built as a FAANG-caliber portfolio piece, correctly using the JDK's modern structured concurrency API — including reasoning about its preview status — is a stronger signal than reproducing patterns available in Java 8.

## Tradeoffs

- **Preview-API risk.** `StructuredTaskScope` has moved through seven JDK release cycles (JEP 428 and 437 as an incubator API in JDK 19/20, then five successive preview iterations — JEP 453, 462, 480, 499, 505 — through JDK 21-25) with breaking API changes at nearly every step (e.g., `Joiner` replacing the original `Subtask.Scope` handler model), and it is confirmed still preview (not finalized) in the Java 25 GA release — every module and JVM that actually compiles or runs `StructuredTaskScope` code must run with `--enable-preview` (not the whole fleet — services that never fork a scope don't need the flag at all), which affects deployment for those services specifically, CI (`--enable-preview` on javac and the test runner for the affected modules), and forward compatibility (a future finalized API may again change method signatures). We accept this risk consciously and isolate it behind a thin internal wrapper (`ConcurrentWorkflow<T>` or similar) so a future API shift touches one module, not every service.
- **Virtual-thread-per-subtask has a floor cost.** Each forked subtask still carries scheduling and stack overhead; for very fine-grained, CPU-bound work (not our use case here, which is I/O-bound fanout) a scope is the wrong tool and a fixed thread pool with batching would be cheaper.
- **Scopes are inherently lexical.** Subtasks cannot outlive the enclosing block, which is the point, but it means genuinely fire-and-forget background work (e.g., an audit log write that should survive the request) must not be forked inside a request-scoped `StructuredTaskScope` — it needs its own lifecycle (e.g., an event published to Kafka) instead. Misusing the pattern for that case would silently cancel the write on scope exit.
- **Less mature tooling than `CompletableFuture`.** Debuggers, thread dump analysis, and some APM agents have had years to build `CompletableFuture`-aware tooling; structured-concurrency-aware tooling is newer and less consistently supported across the observability stack (some OpenTelemetry Java agent instrumentation for virtual threads and scopes is still catching up as of this writing).

## Consequences

- All grouped-fanout code (dashboard aggregation, AI recommendation gathering, search fanout, blockchain verification, smart scheduler evaluation) follows one consistent pattern, which shortens code review and onboarding for that class of workflow.
- Service startup and CI pipelines must set `--enable-preview` (or the equivalent finalized-feature flag once GA) consistently across every module that forks a scope, and the build must fail loudly if a module uses the API without the flag.
- Dashboard aggregation specifically needs a custom `Joiner` (not the built-in shutdown-on-failure) so that a slow or failing subsystem (e.g., blockchain proof status) degrades that one dashboard section instead of failing the entire request — this must be implemented and tested explicitly, it is not the default behavior.
- Timeout handling for each scope must be set deliberately per workflow (dashboard aggregation needs a tight budget, e.g., 800ms, to stay interactive; video processing pipelines need a much longer one) — a single global default would be wrong for at least one of these callers.
- The internal wrapper module around `StructuredTaskScope` becomes a required dependency for every service doing fanout, which is a small coupling cost but keeps the preview-API surface centralized for when it changes again.

## When This Decision Would Be Wrong

This decision should be revisited if any of the following occur:
- **A subsequent preview iteration (post-505) breaks the API again, or finalization keeps slipping past another LTS cycle** — the API is already confirmed still-preview through Java 25 GA, so this isn't a hypothetical; it's a question of how many more iterations we're willing to track before treating the delay itself as a signal. If finalization stalls past the next LTS, falling back to `CompletableFuture` with an explicit cancellation-token pattern for the affected services becomes the safer near-term choice rather than pinning to a moving preview API in production.
- **A workflow's fan-out shape shifts from "small, bounded group of subtasks" to "large, dynamic, backpressure-sensitive stream"** — e.g., if search fanout grows from 5-6 fixed downstream calls to querying dozens of dynamically-discovered shards with rate limiting and partial-result streaming, that is Reactor's problem domain, not structured concurrency's, and the tradeoff calculus reverses.
- **The team scales and most contributors are not JVM specialists** — structured concurrency is still a newer idiom with less Stack Overflow/tutorial coverage than `CompletableFuture`; if hiring shifts toward engineers without deep Java backgrounds, the maintainability advantage assumed here may not hold and the internal wrapper's documentation/training cost would need to be re-evaluated.

## How We Will Validate It

- **Load test dashboard aggregation** under concurrent load (target: p99 latency ≤ 800ms for the full 6-way fanout at 200 req/s sustained) and confirm via thread dump / JFR sampling that no virtual threads survive past scope exit on both the success path and a forced-timeout path.
- **Chaos-test cancellation propagation**: inject an artificial hang in one subtask (e.g., finance service) and assert, via a unit test using `StructuredTaskScope` directly, that the other subtasks are interrupted within a bounded window (target: <50ms after scope exit) rather than continuing to run.
- **Track orphaned-task count as an explicit metric**: instrument the internal wrapper to emit a counter of forked-but-not-joined subtasks at scope close; this should be zero in steady state, and any nonzero reading pages the owning team as a correctness bug, not just a performance one.
- **Re-run the benchmark comparison against `CompletableFuture.allOf` once per quarter** (or immediately on any JDK upgrade) to confirm throughput and memory overhead remain comparable or better, and to catch any preview-API behavioral drift early.
