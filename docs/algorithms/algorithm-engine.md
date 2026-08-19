# Shared Algorithm Engine

`contracts:algorithm-engine` is the Java 25 shared library for bounded, deterministic planning
primitives. It is deliberately a pure Java module: domain services authorize and project their own
data first, then call an algorithm with already-authorized identifiers and bounded input. This keeps
the library free of service/database dependencies and avoids turning it into an unreviewed data
access path.

## Included primitives

| Primitive | Product use case | Complexity | Safety boundary |
| --- | --- | --- | --- |
| `BoundedTopologicalOrder` | Task/Goal execution ordering | O(V + E) time and space | 10,000 nodes; 50,000 submitted edges by default; cycle rejects the whole result |
| `BoundedIntervalConflictDetector` | Calendar overlap detection | O(N log N + K) time; O(N + K) space | 10,000 intervals; 50,000 returned conflicts; half-open `[start,end)` intervals |
| `BoundedPriorityRanker` | Calendar focus suggestions and future planning queues | O(N log N) time; O(N) space | 10,000 candidates; caller-specified bounded result count |

All three use first-seen input order as their final tie-breaker. They never use mutable global
state, wall-clock time, network calls, or user data logging. Invalid, null, cyclic, or oversized
input fails with a controlled `AlgorithmInputException`/`AlgorithmCycleException` before a partial
result is returned.

## Integration rule

Services must not pass client-controlled ownership, tenant, or resource facts directly into this
module. They first load local trusted data, enforce authorization and their own query caps, then map
to these value objects. This is especially important for the future persisted Task/Goal graph and
Calendar schedule projection.

## Verification

The module has deterministic unit tests for stable ordering, duplicate normalization, cycles,
half-open interval boundaries, bounded output, priority ties, malformed input, and benchmark-report
generation. Run:

```text
./gradlew :contracts:algorithm-engine:check
./gradlew :contracts:algorithm-engine:runAlgorithmBenchmarks
```

The second command writes a correctness-checked local JSON artifact under
`contracts/algorithm-engine/build/reports/benchmarks/`. It is a smoke benchmark rather than a JMH
baseline; see [the recorded methodology](../benchmarks/2026-08-18-algorithm-engine-smoke.md) before
using it in a performance claim.

## Interview framing

The design separates reusable algorithmic correctness from domain trust boundaries. Kahn ordering
does not know what a goal is, interval sweep logic does not know calendar content, and ranking does
not make product decisions. Each is bounded and deterministic so a service can explain the result,
test it independently, and retain responsibility for authorization, persistence, and UX.

For runnable product scenarios, correctness rationale, edge cases, and direct test links, see
[Product-Backed Algorithm Examples](product-backed-examples.md). They are intentionally examples,
not a second source of domain behavior.
