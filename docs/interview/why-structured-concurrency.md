# Why Structured Concurrency for Grouped Concurrent Workflows

I want to be upfront about where this stands today: no code in LifeOS uses structured concurrency yet. Both services that exist right now — identity-service (account registration) and task-goal-service (goal CRUD plus a Kahn's-algorithm topological sort for goal dependencies) — are single-path request/response endpoints. Nothing fans out to multiple concurrent calls, so there's nothing for `StructuredTaskScope` to coordinate yet. What I do have is the decision made and documented, and virtual threads already turned on (`spring.threads.virtual.enabled=true`) in both services, so the runtime foundation is in place before I need it.

The reason I made this call now instead of waiting is that the target architecture has several workflows that are obviously "one logical unit of work, many concurrent subtasks" — the clearest example is dashboard aggregation, where a single user-facing request would need to pull from tasks, calendar, finance, AI recommendations, notifications, and blockchain proof status simultaneously and return when the group is done, not when each call happens to finish independently.

If I asked myself "why not just use `CompletableFuture.allOf`," the honest answer is cancellation. With composed futures, cancelling the composed result doesn't actually interrupt the underlying work — if a finance-service call hangs, it keeps running on a pooled thread even after the caller has timed out and moved on. `StructuredTaskScope` ties child-thread lifetime to a lexical scope: when the scope exits, by success, failure, or cancellation, every forked virtual thread is guaranteed to be interrupted and joined before control returns. That's a structural guarantee against orphaned threads, not a convention I'd have to enforce by code review.

I also looked at Reactor for this. It has real backpressure primitives, but I'm not adopting it as a general async model for LifeOS, and introducing it only for fan-out would mean supporting two concurrency stories in the same codebase. Structured concurrency composes with plain try/catch and reads like sequential code, which is a better fit alongside virtual threads.

When a service actually needs fan-out — dashboard aggregation is the first candidate — I'd wrap `StructuredTaskScope` in a thin internal module so the preview-API surface (it's moved through five JEP iterations with breaking changes) is isolated to one place rather than scattered across every service that forks a scope.

Relevant ADRs: [ADR-003](../adr/ADR-003-use-structured-concurrency.md)
