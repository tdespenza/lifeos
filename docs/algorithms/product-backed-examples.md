# Product-Backed Algorithm Examples

## Overview

[`ProductAlgorithmExamples`](../../contracts/algorithm-engine/src/main/java/com/lifeos/algorithms/examples/ProductAlgorithmExamples.java) is a small, deterministic set of interview-practice examples built on top of the shared `contracts/algorithm-engine` primitives — [`BoundedTopologicalOrder`](../../contracts/algorithm-engine/src/main/java/com/lifeos/algorithms/graph/BoundedTopologicalOrder.java), [`BoundedIntervalConflictDetector`](../../contracts/algorithm-engine/src/main/java/com/lifeos/algorithms/interval/BoundedIntervalConflictDetector.java), and [`BoundedPriorityRanker`](../../contracts/algorithm-engine/src/main/java/com/lifeos/algorithms/ranking/BoundedPriorityRanker.java). Each example fixes small, opaque, hand-picked input (never user data or the current clock) so its output is reproducible and the reasoning behind it can be explained end-to-end, the way it would be in an interview. Each example corresponds to a real LifeOS product scenario — Task/Goal dependency ordering, Calendar conflict detection, and Calendar focus-queue ranking — but the examples themselves are illustrations of the primitives, not the production call sites. A production service authorizes and bounds its own input before invoking the same primitives directly.

## Example 1: Task/Goal Execution Order

### Real Product Use Case

[`taskGoalExecutionOrder()`](../../contracts/algorithm-engine/src/main/java/com/lifeos/algorithms/examples/ProductAlgorithmExamples.java) mirrors the "goal dependency mapping" scenario: a Goal (`goal:launch`) has one prerequisite Task (`task:research`) and one dependent Task (`task:build`), which in turn has its own dependent Task (`task:review`). The example asks for a single valid execution order across all four items.

### Why This Algorithm

`BoundedTopologicalOrder` implements Kahn's algorithm — see [`BoundedTopologicalOrder`'s class Javadoc](../../contracts/algorithm-engine/src/main/java/com/lifeos/algorithms/graph/BoundedTopologicalOrder.java) for the full complexity and tie-breaking argument. It is reused here unchanged; the example only supplies the fixed nodes and edges.

### Correctness Argument For This Input

The edges are `task:research -> goal:launch`, `goal:launch -> task:build`, and `task:build -> task:review` — a single chain with no branching and no cycle. `task:research` is the only node with in-degree zero, so it is first by construction; every other node becomes ready only after its sole predecessor is emitted. The result is therefore forced to be `[task:research, goal:launch, task:build, task:review]`, regardless of the algorithm's tie-breaking rule, which makes this a useful example for verifying the ordering direction (prerequisite before Goal, Goal before dependent Tasks) rather than the tie-breaking behavior itself.

### Failure Boundaries

Reusing `BoundedTopologicalOrder` means this example inherits its bounds and failure modes directly: a `null` node or edge collection, a `null` edge, or exceeding `DEFAULT_MAX_NODES` / `DEFAULT_MAX_SUBMITTED_EDGES` throws `AlgorithmInputException`; a cycle throws `AlgorithmCycleException` before any partial order is returned. None of these are reachable with this example's fixed input — they are the same boundaries a caller wiring in real Task/Goal data must handle.

### Interview Explanation

"I have a Goal with a prerequisite Task and two dependent Tasks chained after it. I model that as a 4-node, 3-edge DAG and run Kahn's algorithm: `task:research` is the only node with no incoming edges, so it goes first, then `goal:launch` becomes ready, then `task:build`, then `task:review`. Because it's a single chain, there's only one valid order — a nice property for a first example, since it isolates 'does the edge direction mean what I think it means' from the algorithm's tie-breaking behavior, which the general-purpose benchmark input in `AlgorithmBenchmarkMain` exercises instead."

## Example 2: Calendar Conflict Detection

### Real Product Use Case

[`calendarConflicts()`](../../contracts/algorithm-engine/src/main/java/com/lifeos/algorithms/examples/ProductAlgorithmExamples.java) mirrors detecting overlapping Calendar events: a planning block, a review block that starts before the planning block ends, and a focus block that starts exactly when the review block ends.

### Why This Algorithm

`BoundedIntervalConflictDetector` is reused unchanged — see its [class Javadoc](../../contracts/algorithm-engine/src/main/java/com/lifeos/algorithms/interval/BoundedIntervalConflictDetector.java) for the sweep-line argument, complexity, and the half-open-interval semantics that make adjacent events non-conflicting.

### Correctness Argument For This Input

`event:planning` is `[09:00, 10:00)`, `block:review` is `[09:45, 10:00)`, and `block:focus` is `[10:00, 10:30)`. `event:planning` and `block:review` overlap (`09:45 < 10:00` and `09:00 < 10:00`), so they are the one true conflict. Both `event:planning` and `block:review` end exactly when `block:focus` starts — they are adjacent to it, not overlapping it — and half-open interval semantics treat a shared boundary as available, so neither pairs with `block:focus`. The expected result is exactly one conflict: `(event:planning, block:review)`.

### Failure Boundaries

Same as the underlying detector: a `null` interval collection or a `null` interval throws `AlgorithmInputException`, as does exceeding `DEFAULT_MAX_INTERVALS`, and discovering more than `DEFAULT_MAX_CONFLICTS` conflicts throws instead of returning a truncated result. `IntervalConflict` additionally rejects being constructed directly from a non-overlapping pair, which this example never attempts since all pairs it returns come from the detector's own sweep.

### Interview Explanation

"Three Calendar items, half-open intervals so a block ending at 10:00 doesn't conflict with one starting at 10:00 — that boundary rule is the crux of the example. I sort by start time, sweep forward evicting anything whose end time is at or before the current item's start, and record a conflict against everything still active. Here that produces exactly one conflict, between the planning event and the review block, while the review/focus boundary at 10:00 is correctly treated as available."

## Example 3: Calendar Focus Queue Ranking

### Real Product Use Case

[`calendarFocusQueue()`](../../contracts/algorithm-engine/src/main/java/com/lifeos/algorithms/examples/ProductAlgorithmExamples.java) mirrors ranking already-authorized Calendar work into a focus queue: three candidate Tasks with different priority scores and due times, asking for the top 3.

### Why This Algorithm

`BoundedPriorityRanker` is reused unchanged — see its [class Javadoc](../../contracts/algorithm-engine/src/main/java/com/lifeos/algorithms/ranking/BoundedPriorityRanker.java) for the sort-based argument and complexity. The ranker only orders already-authorized candidates; it does not calculate a priority score or make an AI recommendation.

### Correctness Argument For This Input

`task:prepare` has priority `100`, strictly higher than the other two candidates at priority `80`, so it ranks first regardless of due time. `task:review` (priority `80`, due at `+7200s`) and `task:inbox-zero` (priority `80`, due `null`) tie on priority, and the ranker's contract puts an earlier non-null due time ahead of a `null` one, so `task:review` outranks `task:inbox-zero`. With `limit == 3` and exactly three candidates, the expected result is the full ordering: `[task:prepare, task:review, task:inbox-zero]`.

### Failure Boundaries

Same as the underlying ranker: a `null` candidate collection, a `null` candidate, a `limit` less than `1` or greater than the configured `maxCandidates`, or exceeding `DEFAULT_MAX_CANDIDATES` candidates all throw `AlgorithmInputException`. This example's fixed input and `limit` stay well inside every bound.

### Interview Explanation

"Three candidates, ranked by priority score first and then by earlier due time as the tie-break, with no due time ranked last. `task:prepare` wins outright on score. The other two tie on score, so the due-time rule decides: `task:review` has an actual deadline and `task:inbox-zero` doesn't, so `task:review` goes first among the tied pair. It's a small example, but it exercises every branch of the ranker's comparator — score, tie-break by deadline, and the null-deadline-last rule — in one pass."

## Test Cases

See [`ProductAlgorithmExamplesTest`](../../contracts/algorithm-engine/src/test/java/com/lifeos/algorithms/examples/ProductAlgorithmExamplesTest.java): one test per example, each asserting the exact expected output derived above.

## Benchmark Results

These three examples are not independently benchmarked — they are fixed, tiny inputs meant for correctness and explanation, not throughput measurement. [`AlgorithmBenchmarkMain`](../../contracts/algorithm-engine/src/main/java/com/lifeos/algorithms/benchmark/AlgorithmBenchmarkMain.java) benchmarks the same three underlying algorithm classes directly, at a larger synthetic scale (1,000–5,000 elements) designed to exercise their tie-breaking and eviction logic under load; see that class and its generated `build/reports/benchmarks/algorithm-engine.json` report for measured medians and p95s.
