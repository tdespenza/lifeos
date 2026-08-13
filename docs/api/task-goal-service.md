# task-goal-service API

Base URL (local): `http://localhost:8082`

Status: authenticated, owner- and tenant-scoped goal create/list/read plus a real graph-algorithm
endpoint for dependency ordering. Tasks, habits, routines, and milestones from the Task and Goal
Service scope are not modeled yet.

## Authorization boundary

Every `/api/v1/goals` operation requires an access-token bearer header:

```text
Authorization: Bearer <short-lived LifeOS access token>
```

The service calls the identity service's internal validation endpoint, which checks JWT claims and
the durable session/revocation state. It then loads or derives goal owner/tenant facts and requests
a separate identity authorization decision. This service, rather than a gateway, remains responsible
for enforcing the object-level result.

Goals created under this version persist the validated account as `ownerAccountId` and its personal
tenant UUID string as `tenantId`. Neither comes from request JSON. Ownerless legacy rows fail closed
during a rolling upgrade and are not exposed. Internal identity calls use deployment-managed workload
credentials, bounded 2-second connection / 3-second read timeouts, and no permissive fallback.
The configured identity `base-url` must use HTTPS unless it targets a local loopback development
endpoint (for example, `localhost`, `127.0.0.1`, or `[::1]`), so workload credentials and
authorization decisions are encrypted whenever they leave the local host.

| Status | Condition | Body |
| --- | --- | --- |
| `401 Unauthorized` | Missing, malformed, expired, revoked, or invalid bearer credential; the identity validation adapter deliberately uses the same response when it rejects the calling workload | `{ "error": "Authentication required" }` plus `WWW-Authenticate: Bearer` |
| `403 Forbidden` | Any policy deny, including a different user's goal or a goal that does not exist | `{ "error": "Access denied" }` |
| `503 Service Unavailable` | Identity validation/decision transport, rate-limit, audit, or policy dependency cannot complete safely | `{ "error": "Authorization temporarily unavailable" }` plus `Retry-After: 1` |

The `403` representation is deliberately identical for an existing goal owned by someone else and
a non-existent goal. Do not use it to infer resource existence. Neither endpoint returns raw bearer
values, workload credentials, policy records, owner IDs, or tenant IDs.

## `POST /api/v1/goals`

Create a goal owned by the validated subject. The identity policy action is `goal:create`; it requires
both a permitted role and attributes showing the subject owns its personal tenant.

### Request body

```json
{ "title": "Land a Staff Engineer role" }
```

`title` must be non-blank. Owner and tenant fields are intentionally absent from the public contract.

### Responses

| Status | Condition | Body |
| --- | --- | --- |
| `201 Created` | Authenticated subject is allowed to create its personal goal | `GoalResponse`; `Location: /api/v1/goals/{id}` |
| `400 Bad Request` | Blank title | Spring validation error body; values must not be treated as authorization facts |
| `401`, `403`, `503` | Authorization-boundary failures | Generic body defined above |

## `GET /api/v1/goals`

List only goals owned by the validated subject in that subject's tenant. It does not return goals
owned by another account, even if a caller guesses their identifiers. The identity policy action is
`goal:list`.

### Response (`200 OK`)

```json
[
  {
    "id": "aec92835-a053-4811-980c-f2a8e67a46c2",
    "title": "Land a Staff Engineer role",
    "createdAt": "2026-07-31T04:10:26.871896Z"
  }
]
```

## `GET /api/v1/goals/{goalId}`

Read one goal after loading its trusted persisted owner and tenant facts and obtaining a
`goal:read` decision. Members can read their own goal; a scoped tenant administrator may be allowed
by the identity policy for another owner in its explicit tenant scope.

For a non-existent goal, task-goal-service submits trusted `resourceExists=false` facts to the
policy boundary, receives an auditable bounded denial, and still returns the exact same generic
`403` representation as an unauthorized existing goal.

| Status | Condition | Body |
| --- | --- | --- |
| `200 OK` | Authenticated and allowed | `GoalResponse` |
| `401`, `403`, `503` | Authorization-boundary failures | Generic body defined above |

## `POST /api/v1/goals/dependency-order`

Resolve a valid execution order for a supplied graph after the authenticated subject passes the
`goal:dependency-order` authorization decision. The algorithm does not fetch or mutate persisted
goals; it operates only on the supplied graph. See
[`docs/algorithms/topological-sort-goal-dependencies.md`](../algorithms/topological-sort-goal-dependencies.md).

### Request body

```json
{
  "goals": ["Learn DSA", "Build Portfolio Project", "Apply to FAANG", "System Design Practice"],
  "dependencies": [
    { "before": "Learn DSA", "after": "Apply to FAANG" },
    { "before": "Build Portfolio Project", "after": "Apply to FAANG" },
    { "before": "Learn DSA", "after": "System Design Practice" },
    { "before": "System Design Practice", "after": "Apply to FAANG" }
  ]
}
```

`goals` must be non-empty; `dependencies` may be omitted or empty. A goal name appearing only in
`dependencies` is included in the returned order because the algorithm collects nodes from both
inputs.

| Status | Condition | Body |
| --- | --- | --- |
| `200 OK` | Authorized valid DAG | `{ "order": [...] }` |
| `409 Conflict` | Dependencies contain a cycle | Plain-text message naming unresolved input nodes |
| `401`, `403`, `503` | Authorization-boundary failures | Generic body defined above |

## `GET /actuator/health`

Same as [identity-service](identity-service.md#operational-endpoints) — only `health` and `info`
are exposed.

## Data store

PostgreSQL database `lifeos_task_goal`, table `goal`:

| Column | Purpose |
| --- | --- |
| `id` | Application-generated goal UUID |
| `title` | Goal title |
| `created_at` | Creation timestamp |
| `owner_account_id` | Immutable authenticated owner for newly created rows |
| `tenant_id` | Immutable tenant scope derived from the owner subject |

The `(owner_account_id, tenant_id)` index supports owner-scoped list queries without a full table
scan. Flyway owns schema evolution and Hibernate runs with `ddl-auto: validate`; see the
[database migration runbook](../operations/database-migrations.md) for deployment and rollback
procedures. Existing ownerless rows are intentionally not backfilled because no safe ownership fact
exists; they remain fail-closed.
