# ADR-024: Persist Task lifecycle commands and Task/Goal dependency graphs

**Status:** Accepted

## Context

Epic 5 originally persisted only Goals. `POST /api/v1/goals/dependency-order` accepted an
ephemeral graph of client labels, which demonstrated a topological sort but could not represent a
user's actual planning state. A Task lifecycle also needs to survive restarts, reject stale
concurrent writes, and make client retries safe without disclosing another user's data.

The graph crosses two tables (`task` and `goal`), so a conventional database foreign key cannot
protect every endpoint of a polymorphic edge. Concurrent requests for `A -> B` and `B -> A` must
not both validate an empty graph and commit a cycle.

## Decision

- Persist owner- and tenant-scoped Tasks with immutable ownership, an optimistic `version`, and
  terminal `COMPLETED` / `CANCELED` lifecycle states. Public lifecycle mutations require a strong
  numeric `If-Match` ETag and a bounded `Idempotency-Key`.
- Store a durable, SHA-256-digest-only Task command reservation for create, update, complete, and
  cancel. The reservation preallocates a Task ID for creation, is committed independently, and
  stores an immutable response snapshot with the mutation. An interrupted create can therefore be
  resumed by an authorized matching retry; a completed mutation returns its exact original
  snapshot rather than applying the command again.
- Persist Task/Goal edges in `task_goal_dependency`, scoped by immutable owner/tenant values. The
  service loads both nodes from owner-scoped repositories before entering a transaction; missing
  and cross-user IDs return the same generic denial. Self-edges, absent nodes, and candidate
  cycles are rejected before the edge transaction commits.
- Serialize graph edge additions/removals through one short-lived durable
  `task_goal_dependency_guard` row per owner/tenant. It prevents concurrent opposite edge writes
  from passing separate cycle checks. It does not serialize unrelated task lifecycle writes or
  another user's graph.
- Use the shared `contracts:algorithm-engine` `BoundedTopologicalOrder` Kahn primitive for both
  persisted ordering and the retained free-text compatibility endpoint. The persisted projection
  retrieves owner-scoped nodes and edges in a documented deterministic order, then runs the graph
  traversal in O(V + E) time and O(V + E) memory after retrieval. Its 10,000-node/50,000-edge
  bounds fail without emitting a partial order.

## Consequences

This provides durable retry recovery, protects the personal ownership boundary locally even if a
future authorization policy adds administrators, and makes graph mutation concurrency explicit.
The graph lock is intentionally coarse per personal planning graph: it favors simple correctness
over parallel edge writes for a workload bounded to a single user's tasks and goals. If shared
household graphs become a product requirement, replace that guard with a reviewed partitioned
locking strategy and add a tenancy-aware edge model.

No polymorphic database foreign keys are present. The application performs scoped existence checks
both before and inside the guard transaction; migrations retain database uniqueness, type, and
self-edge checks as a defense against direct data corruption. Terminal tasks and goals remain in
the order projection to preserve historical graph truth; presentation clients decide whether to
hide completed work.

## Verification

`TaskLifecycleIntegrationTest`, `TaskIdempotencyRecoveryIntegrationTest`, and
`PersistedDependencyIntegrationTest` cover H2/Flyway lifecycle, replay/recovery, owner scope,
self/cycle rejection, idempotent edge changes, and ordering. `TaskAndDependencyPostgresIntegrationTest`
uses real PostgreSQL Testcontainers for concurrent matching Task creates and opposite-edge cycle
admission.
