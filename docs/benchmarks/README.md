# Benchmarks

**Status: bounded algorithm and performance-lab smoke harnesses have been run.** Their host-specific
methodology and observations are recorded in
[`2026-08-18-algorithm-engine-smoke.md`](2026-08-18-algorithm-engine-smoke.md),
[`2026-08-18-performance-lab-smoke.md`](2026-08-18-performance-lab-smoke.md), and
[`2026-08-18-concurrency-lab-smoke.md`](2026-08-18-concurrency-lab-smoke.md). They prove
correctness-checked harnesses, not a production throughput claim or JMH baseline. All other entries
below remain unmeasured unless a dated result document says otherwise.

The full target benchmark list is mirrored in `docs/epics.md` (FR85–FR87). Recommended tooling
per the tracked testing guidance is [k6](https://k6.io/) for load tests and
[JMH](https://openjdk.org/projects/code-tools/jmh/) for micro-benchmarks (platform vs. virtual
threads, `CompletableFuture` vs. structured concurrency). The ignored `REQUIREMENTS.md` source
document is not required for reproducing these local fixtures.

## Planned benchmarks and their prerequisites

| Benchmark | Blocked on | Status |
| --- | --- | --- |
| Platform threads vs. virtual threads | A real concurrent-I/O workload to benchmark against | Bounded lab smoke measured; production load not started |
| `CompletableFuture` vs. structured concurrency | A production fan-out workload | Bounded lab smoke measured; production load not started |
| REST vs. gRPC latency | A deployed paired endpoint and representative payload | Contracts and three opt-in gRPC hosts exist; latency run not started |
| GraphQL aggregation performance | A representative authenticated dashboard workload | Bounded GraphQL exists; run not started |
| PostgreSQL query plans | A provisioned identity/task-goal Postgres schema and representative data | Opt-in read-only identity email-plan probe added; production query-plan result not claimed |
| Redis rate-limit and challenge-state latency and operation outcomes | No benchmark harness or representative load profile exists yet | Not started |
| Vector search latency | No vector database exists yet | Not started |
| Kafka/Pulsar event throughput | A provisioned broker with production ACLs and retention | Local eventing profile exists; run not started |
| Video processing pipeline latency | External transcoder/worker and representative media fixture | Media control plane exists; processing pipeline pending |
| Blockchain proof anchoring latency | Contract deployment and a running local anchor worker | Local Besu/contract foundation exists; latency run not started |
| JVM GC tuning results | Feasible today under a synthetic load test, not yet done | Not started, unblocked |
| Shared planning algorithms | `contracts:algorithm-engine` deterministic smoke harness | Measured once; no regression target |

## What's actually feasible right now

Two benchmarks in the table above have no blocker other than not having been run: PostgreSQL query-plan analysis and a basic JVM/GC profile for `identity-service` / `task-goal-service` under load. When either is run, the results (with methodology, hardware/environment, and reproduction steps) belong as a dated entry in this directory — e.g. `docs/benchmarks/2026-XX-XX-postgres-query-plans.md` — rather than overwriting this file, so historical results stay comparable across runs.
