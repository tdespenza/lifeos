# Concurrency — Current State

The full concurrency strategy is defined in `REQUIREMENTS.md`'s "Java 25 Concurrency Strategy" section and justified in [ADR-002](../adr/ADR-002-use-virtual-threads.md), [ADR-003](../adr/ADR-003-use-structured-concurrency.md), and [ADR-004](../adr/ADR-004-use-scoped-values.md). This document tracks what's actually enabled and exercised today versus what's still ahead.

## Virtual threads — enabled, lightly exercised

Both `identity-service` and `task-goal-service` set `spring.threads.virtual.enabled: true` in their `application.yml` ([identity](../../services/identity-service/src/main/resources/application.yml), [task-goal](../../services/task-goal-service/src/main/resources/application.yml)), so every request is handled on a virtual thread rather than a pooled platform thread.

In practice, neither service does enough concurrent I/O per request yet for this to be meaningfully stress-tested: each request does at most one JDBC round-trip to its own PostgreSQL database. Virtual threads are correct and free here, but the real payoff — thousands of concurrently in-flight requests without exhausting a platform thread pool — hasn't been demonstrated under load. No load test has been run against either service (see [`docs/benchmarks/README.md`](../benchmarks/README.md)).

## Structured concurrency — not yet used

No code in the repository uses structured concurrency (`StructuredTaskScope` or equivalent) yet. There is currently no endpoint that needs to fan out multiple concurrent calls and join them under one cancellation scope — both services are single-path (one request in, one JDBC call, one response out). The first real use case will likely be a future dashboard-aggregation endpoint (see the "Load dashboard" example in [ADR-003](../adr/ADR-003-use-structured-concurrency.md)) or the AI orchestrator's parallel provider calls, neither of which is built yet.

## Scoped values — not yet used

No code uses scoped values yet, for the same reason: there's no request-scoped context (user identity, correlation ID, tenant) being propagated through nested calls today, because there's no authentication and no internal service-to-service calls. This becomes relevant once identity-service issues real request context that other services need to read (e.g. a correlation ID threaded through a gRPC call), per [ADR-004](../adr/ADR-004-use-scoped-values.md).

## Why this is being tracked honestly rather than assumed

It would be easy to write ADRs for virtual threads / structured concurrency / scoped values and let a reader assume all three are exercised in production-shaped code. Only one of the three actually is, today. This doc exists so "why virtual threads" (the decision, in [ADR-002](../adr/ADR-002-use-virtual-threads.md)) and "is it actually being used" (the fact, here) don't get conflated — a common and avoidable failure mode in portfolio projects.
