# Topological Sort — Goal Dependency Ordering

## Real Product Use Case

`task-goal-service` exposes `POST /api/v1/goals/dependency-order` (see [`GoalController`](../../services/task-goal-service/src/main/java/com/lifeos/taskgoal/goal/GoalController.java)). Given a set of goal names and a set of directed dependency edges (`{before, after}`, meaning `before` must complete before `after`), it returns a valid execution order — or a 409 Conflict if the dependencies contain a cycle. This backs the "goal dependency mapping" use case in [REQUIREMENTS.md](../../REQUIREMENTS.md): e.g. ordering "Learn DSA" → "System Design Practice" → "Apply to FAANG".

## Why This Algorithm Was Chosen

Kahn's algorithm (BFS-based topological sort) was chosen over a DFS-based topological sort because it naturally produces cycle detection as a side effect of the same pass (any node whose in-degree never reaches zero is part of, or downstream of, a cycle) without needing a separate recursion-stack/"visiting" state machine. It's also iterative, avoiding recursion-depth concerns for large dependency graphs.

## Alternatives Considered

- **DFS-based topological sort** (post-order reversal): equally correct and same asymptotic complexity, but cycle detection requires tracking a separate "currently on the recursion stack" set, and the implementation is naturally recursive (stack-depth risk for very large or deeply chained goal graphs).
- **Naive repeated scanning** (repeatedly scan for a zero-dependency node, remove it, repeat): O(V²) in the worst case since each removal re-scans all remaining nodes — unnecessary given Kahn's algorithm achieves the same result in linear time with an explicit queue.

## Time Complexity

O(V + E), where V is the number of goals and E is the number of dependency edges. Each node is enqueued and dequeued exactly once; each edge is relaxed exactly once when its source is processed.

## Space Complexity

O(V + E): the adjacency list holds all edges, and the in-degree map and result list each hold at most V entries.

## Java 25 Implementation Notes

See [`TopologicalSortService`](../../services/task-goal-service/src/main/java/com/lifeos/taskgoal/goal/algorithm/TopologicalSortService.java). Implementation notes:

- Uses `ArrayDeque<String>` as the ready-queue rather than `LinkedList`, avoiding the extra per-node allocation overhead of a linked-list-backed deque.
- Uses `LinkedHashSet` to collect all node names (goals passed explicitly, plus any additional nodes only referenced via an edge) so iteration order is deterministic for a given input, which keeps the algorithm's output reproducible for the same input and makes tests deterministic.
- `DependencyEdge` is a plain `record(String before, String after)` — no behavior, just a named tuple, which reads more clearly at call sites than a raw `Map.Entry` or two parallel lists would.
- On a cycle, throws [`CyclicDependencyException`](../../services/task-goal-service/src/main/java/com/lifeos/taskgoal/goal/algorithm/CyclicDependencyException.java) carrying the specific unresolved node names (not just "a cycle exists"), which `GoalController` maps to an HTTP 409 with that detail in the body — actionable for a caller trying to fix their input, not just a generic error.

## Failure Cases

- **Direct cycle** (`A → B`, `B → A`): detected — neither node ever reaches in-degree zero.
- **Indirect cycle** (`A → B → C → A`): detected — all three nodes remain unresolved.
- **Cycle isolated from unrelated nodes**: a cycle among `{A, B}` does not prevent an unrelated `Standalone` node (no edges) from being correctly ordered — but since the overall result size won't match the total node count, the whole request still fails with 409, listing only the actually-cyclic nodes in the exception (unaffected nodes work fine standalone since Kahn's algorithm still resolves and outputs them; they're just not returned because the request as a whole is rejected).
- **Empty edge list**: every goal has in-degree zero and is returned immediately, in insertion order.

## Test Cases

See [`TopologicalSortServiceTest`](../../services/task-goal-service/src/test/java/com/lifeos/taskgoal/goal/algorithm/TopologicalSortServiceTest.java): orders a simple chain, allows independent branches to interleave in either valid relative order, includes nodes with no dependencies, detects a direct 2-node cycle, detects an indirect 3-node cycle, and confirms a cycle's unresolved-node list excludes unrelated standalone nodes. See also [`GoalControllerTest`](../../services/task-goal-service/src/test/java/com/lifeos/taskgoal/goal/GoalControllerTest.java) for the HTTP-layer contract (200 with ordered `order[]`, 409 on cycle).

## Benchmark Results

None yet — the current goal graphs in this project are small (a handful of goals per user), so no throughput/latency benchmark has been run against this endpoint. Given the O(V + E) complexity, this is not expected to be a bottleneck at any realistic personal-goal-graph size; if goal graphs ever grow large enough to matter (e.g. a shared/collaborative goal graph across many users), a JMH benchmark should be added under `labs/performance-lab/` per [REQUIREMENTS.md](../../REQUIREMENTS.md#performance-and-benchmarking) before assuming this remains a non-issue.

## Interview Explanation

"I needed to order a user's goals so that every goal appears after everything it depends on — a classic topological sort. I used Kahn's algorithm specifically because cycle detection falls out of the same linear pass: if I can't process every node, whatever's left over is exactly the cycle, and I can tell the caller precisely which goals are stuck in a circular dependency instead of just saying 'invalid input.' It's O(V + E), so it stays fast even as a user's goal graph grows, and the whole thing is unit-tested for both the happy path and the cycle-detection path."
