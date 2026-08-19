# Product-Backed Algorithm Examples

This is the interview-practice companion for the shared Algorithm Engine. The runnable examples
use fixed opaque identifiers and timestamps; they never query a database, choose an authorization
scope, or handle user data. A domain service must do those things before calling a bounded
algorithm primitive.

The exact examples live in
[`ProductAlgorithmExamples`](../../contracts/algorithm-engine/src/main/java/com/lifeos/algorithms/examples/ProductAlgorithmExamples.java)
and are protected by
[`ProductAlgorithmExamplesTest`](../../contracts/algorithm-engine/src/test/java/com/lifeos/algorithms/examples/ProductAlgorithmExamplesTest.java).

## Task/Goal execution order: Kahn topological sort

**Problem.** A user has a Goal that depends on research, followed by implementation and review.
Return an order in which every prerequisite is completed before the item that needs it.

**Product use case.** `task-goal-service` projects an authenticated owner's persisted Task/Goal
dependency graph and delegates the traversal to `BoundedTopologicalOrder`; it never allows the
library to choose which graph the caller may see.

**Correctness.** Kahn's algorithm starts from all zero-indegree nodes. Removing a node reduces the
indegree of exactly its dependents, so a node joins the output only once all incoming dependency
edges have been removed. If not all nodes are emitted, a cycle remains and the library rejects the
entire result rather than returning a partial execution plan.

**Complexity.** O(V + E) time and O(V + E) space. The reusable primitive caps the default input at
10,000 nodes and 50,000 submitted edges.

**Edge cases and tests.** Duplicate edges are normalized, first-seen ready nodes remain stable,
and direct/indirect cycles, nulls, and over-bound input fail predictably. See the runnable example
test and [`BoundedTopologicalOrderTest`](../../contracts/algorithm-engine/src/test/java/com/lifeos/algorithms/graph/BoundedTopologicalOrderTest.java).

## Calendar conflict detection: sweep line plus active sets

**Problem.** Identify calendar-event and time-block overlaps without flagging adjacent endpoints.

**Product use case.** Calendar normalizes authorized event/time-block occurrences to `Instant`
intervals and uses the shared detector to support conflict reporting. Time-zone and recurrence
expansion policy remain in Calendar, not the primitive.

**Correctness.** After ordering by start time, the min-heap removes every interval ending at or
before the current start. The remaining active set contains exactly the earlier intervals whose
end is after the current start, so each one overlaps the current half-open interval and is emitted
once.

**Complexity.** O(N log N + K) time and O(N + K) space, where K is returned conflict pairs. The
default cap is 10,000 intervals and 50,000 pairs; a dense calendar rejects above that output cap
instead of silently omitting conflicts.

**Edge cases and tests.** Adjacent slots, nested intervals, equal starts, invalid zero-length
intervals, nulls, and output caps are covered by the runnable example and
[`BoundedIntervalConflictDetectorTest`](../../contracts/algorithm-engine/src/test/java/com/lifeos/algorithms/interval/BoundedIntervalConflictDetectorTest.java).

## Explainable focus queue: stable priority ranking

**Problem.** Return a short, explainable focus queue from already-authorized planning candidates.

**Product use case.** Calendar can pass service-owned priority scores and due dates into the
ranker. The library ranks them but does not create a score, infer user intent, or imply an AI
recommendation.

**Correctness.** A total comparator orders higher score first, then earlier non-null due time, then
the first-seen input rank. Therefore equal input produces the same prefix and every later candidate
is no more preferred than an earlier candidate.

**Complexity.** O(N log N) time and O(N) space for at most 10,000 candidates. The requested result
limit is validated before sorting.

**Edge cases and tests.** Equal scores/deadlines retain input order, absent deadlines sort after
real deadlines of equal score, and null/oversized input is rejected. See the runnable example and
[`BoundedPriorityRankerTest`](../../contracts/algorithm-engine/src/test/java/com/lifeos/algorithms/ranking/BoundedPriorityRankerTest.java).

## Verification

Run the implementation and example tests together:

```text
./gradlew :contracts:algorithm-engine:check
```

The benchmark fixture is separate because it records machine-dependent measurements:

```text
./gradlew :contracts:algorithm-engine:runAlgorithmBenchmarks
```

Read the recorded methodology before comparing runs:
[`2026-08-18-algorithm-engine-smoke.md`](../benchmarks/2026-08-18-algorithm-engine-smoke.md).
