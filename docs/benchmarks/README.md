# Benchmarks

**Status: no benchmarks have been run yet.** This document exists to record the plan and methodology, not to report results — there are no numbers here, and there won't be until the corresponding benchmark is actually executed. Fabricating throughput/latency figures for code that hasn't been measured would defeat the entire purpose of this section.

The full target benchmark list is in `REQUIREMENTS.md`'s "Performance and Benchmarking" section. Recommended tooling per `REQUIREMENTS.md`'s "Testing Strategy" section: [k6](https://k6.io/) for load tests, [JMH](https://openjdk.org/projects/code-tools/jmh/) for micro-benchmarks (platform vs. virtual threads, `CompletableFuture` vs. structured concurrency).

## Planned benchmarks and their prerequisites

| Benchmark | Blocked on | Status |
| --- | --- | --- |
| Platform threads vs. virtual threads | A real concurrent-I/O workload to benchmark against (today's endpoints do at most one JDBC call each) | Not started |
| `CompletableFuture` vs. structured concurrency | Structured concurrency isn't used anywhere yet — see [`docs/concurrency/virtual-threads-current-state.md`](../concurrency/virtual-threads-current-state.md) | Not started |
| REST vs. gRPC latency | No gRPC endpoint exists yet | Not started |
| GraphQL aggregation performance | No GraphQL layer exists yet | Not started |
| PostgreSQL query plans | Feasible today (identity-service, task-goal-service both query real Postgres) but not yet done | Not started, unblocked |
| Redis cache hit ratios | Redis isn't used by any service yet | Not started |
| Vector search latency | No vector database exists yet | Not started |
| Kafka/Pulsar event throughput | No event bus exists yet | Not started |
| Video processing pipeline latency | No media streaming service exists yet | Not started |
| Blockchain proof anchoring latency | No blockchain integration exists yet | Not started |
| JVM GC tuning results | Feasible today under a synthetic load test, not yet done | Not started, unblocked |

## What's actually feasible right now

Two benchmarks in the table above have no blocker other than not having been run: PostgreSQL query-plan analysis and a basic JVM/GC profile for `identity-service` / `task-goal-service` under load. When either is run, the results (with methodology, hardware/environment, and reproduction steps) belong as a dated entry in this directory — e.g. `docs/benchmarks/2026-XX-XX-postgres-query-plans.md` — rather than overwriting this file, so historical results stay comparable across runs.
