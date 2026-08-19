# Concurrency — Current State

The full concurrency strategy is defined in `REQUIREMENTS.md`'s "Java 25 Concurrency Strategy" section and justified in [ADR-002](../adr/ADR-002-use-virtual-threads.md), [ADR-003](../adr/ADR-003-use-structured-concurrency.md), and [ADR-004](../adr/ADR-004-use-scoped-values.md). This document tracks what's actually enabled and exercised today versus what's still ahead.

## Virtual threads — enabled, lightly exercised

All current Spring services set `spring.threads.virtual.enabled: true` in their `application.yml`,
so every request is handled on a virtual thread rather than a pooled platform thread.

In practice, none of the services does enough concurrent I/O per request for this to be meaningfully stress-tested: the domain services are largely single-database paths, while gateway is a bounded chain of Redis and HTTP calls. Virtual threads are correct and free here, but the real payoff — thousands of concurrently in-flight requests without exhausting a platform thread pool — hasn't been demonstrated under load. No load test has been run against the current services (see [`docs/benchmarks/README.md`](../benchmarks/README.md)).

## Structured concurrency — exercised in the gateway and lab

The runnable `labs/concurrency-lab` uses Java 25 preview `StructuredTaskScope` with a bounded
deadline, cancellation, and inherited `ScopedValue`, and compares it with platform/virtual
threads, `ExecutorService`, and `CompletableFuture`. The opt-in gateway GraphQL gRPC dashboard
client now forks the Task, Calendar, and Finance reads in one two-second scope; a timeout cancels
the children and returns an explicit unavailable snapshot. Broader service fan-outs and production
preview-runtime rollout remain unmeasured.

## Scoped values — used for correlation context

All current services bind their validated `X-Correlation-ID` at HTTP ingress with `ScopedValue` and
make it available to nested code while the request is active. The gateway and domain services
explicitly carry that value on their internal calls; the gateway's observation-enabled HTTP client
also propagates the W3C trace context. Authenticated user, tenant, and AI-session context remain
explicit parameters at the existing authorization boundary; the dashboard scope carries the
validated account subject into each gRPC request, while household selection remains unavailable.

## Why this is being tracked honestly rather than assumed

It would be easy to write ADRs for virtual threads / structured concurrency / scoped values and let
a reader assume all three are exercised equally. Virtual threads and the narrow correlation-context
use of scoped values are present across services; structured concurrency is now an executable
gateway fan-out primitive as well as a lab comparison, but the production path has not yet been
load-tested at the target scale. This document keeps those claims separate.
