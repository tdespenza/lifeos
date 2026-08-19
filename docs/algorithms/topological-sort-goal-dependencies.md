# Topological Sort — Task/Goal Dependency Ordering

## Real Product Use Case

`task-goal-service` now exposes `GET /api/v1/dependencies/execution-order` for the authenticated
user's persisted Task/Goal graph. It loads only that owner's real nodes and directed edges, then
returns every node in a valid execution order — or a bounded error without a partial order if data
is cyclic or oversized. This backs the planning use case in `REQUIREMENTS.md`, for example
planning a Goal before its implementation Tasks.

`POST /api/v1/goals/dependency-order` remains a compatibility endpoint for a submitted graph of
goal-name labels and `{before, after}` edges. It is deliberately not persistence: new clients use
the persisted API above.

The compatibility endpoint accepts at most 10,000 distinct nodes, 50,000 submitted edges, and
128 characters per node label. It preserves the first-seen node order as its deterministic
tie-breaker and ignores repeated identical edges. These bounds make its memory use explicit while
the service retains the compatibility endpoint during migration to persisted task/goal identifiers.

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

The shared implementation is
[`BoundedTopologicalOrder`](../../contracts/algorithm-engine/src/main/java/com/lifeos/algorithms/graph/BoundedTopologicalOrder.java).
`task-goal-service`'s
[`TopologicalSortService`](../../services/task-goal-service/src/main/java/com/lifeos/taskgoal/goal/algorithm/TopologicalSortService.java)
is only a compatibility adapter for its stricter free-text validation and unresolved-label error
diagnostic. `PersistedDependencyGraphTransactions` delegates directly to the shared primitive.
Implementation notes:

- Uses `ArrayDeque` plus `LinkedHashMap`/`LinkedHashSet` internally, avoiding recursive depth risk
  and preserving first-seen ready-node ties without a priority queue.
- The persisted projection has a deterministic resource-family and indexed creation/ID order before
  the linear traversal; the graph traversal itself remains O(V + E).
- `DirectedEdge<T>` is a typed immutable record, so the same reviewed primitive can order both
  free-text labels and `(nodeType, UUID)` persisted identities.
- The compatibility adapter retains [`CyclicDependencyException`](../../services/task-goal-service/src/main/java/com/lifeos/taskgoal/goal/algorithm/CyclicDependencyException.java)
  with unresolved labels. The shared primitive intentionally exposes no client node values when it
  rejects a cycle, which avoids leaking unauthorized persisted identifiers.

## Failure Cases

- **Direct cycle** (`A → B`, `B → A`): detected — neither node ever reaches in-degree zero.
- **Indirect cycle** (`A → B → C → A`): detected — all three nodes remain unresolved.
- **Cycle isolated from unrelated nodes**: a cycle among `{A, B}` does not prevent an unrelated `Standalone` node (no edges) from being correctly processed — but since the overall result size won't match the total node count, the whole request still fails with 409. The unresolved list excludes successfully processed standalone nodes, while it can include nodes downstream of the cycle.
- **Empty edge list**: every goal has in-degree zero and is returned immediately, in insertion order.
- **Malformed, duplicate-explicit, or oversized input**: rejected before sorting; repeated identical edges are deliberately normalized rather than multiplying in-degree or work.

## Test Cases

See [`BoundedTopologicalOrderTest`](../../contracts/algorithm-engine/src/test/java/com/lifeos/algorithms/graph/BoundedTopologicalOrderTest.java)
for the shared bounded algorithm, [`TopologicalSortServiceTest`](../../services/task-goal-service/src/test/java/com/lifeos/taskgoal/goal/algorithm/TopologicalSortServiceTest.java)
for compatibility semantics, and
[`PersistedDependencyIntegrationTest`](../../services/task-goal-service/src/test/java/com/lifeos/taskgoal/dependency/PersistedDependencyIntegrationTest.java)
for real Task/Goal edge persistence, scope, cycle, deletion, and order coverage. The PostgreSQL
test additionally verifies two concurrent opposite-edge requests cannot both commit.

## Benchmark Results

The shared primitive has a correctness-checked smoke benchmark, including JVM and machine metadata;
see [the recorded methodology and measured baseline](../benchmarks/2026-08-18-algorithm-engine-smoke.md).
It is not a production endpoint latency SLO or a JMH result. The current personal graph workload is
small, so a service-specific throughput claim still requires an end-to-end benchmark before it is
made. If shared/collaborative graphs become a product feature, add JMH and representative database
projection measurements under `labs/performance-lab/`.

## Interview Explanation

"I needed to order a user's persisted tasks and goals so that every node appears after everything it depends on. I use one shared bounded Kahn implementation: cycle detection falls out of the same linear pass, and no partial plan is returned. The service projects only the caller's graph, serializes concurrent edge mutations per personal graph, and tests the lock behavior on real PostgreSQL. The old label endpoint is explicitly a compatibility adapter, not a second planning data model."
