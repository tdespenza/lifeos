# task-goal-service API

Base URL (local): `http://localhost:8082`

Status: authenticated, owner- and tenant-scoped Goal and Task lifecycles with versioned responses,
durable idempotency, persisted mixed Task/Goal dependencies, bounded execution ordering, and
owner-scoped planning primitives for habits, routines, milestones, and recurring materialization.

## Inbound request deadline

The public Tomcat listener applies `task-goal.inbound-request-timeout` (default `10s`, configurable
from `1ms` through `60s`) to request-line/header reads, idle keep-alive sockets, and request-body
uploads. This bounds stalled clients before they can hold servlet-request resources indefinitely;
it is a socket-read deadline, not a cap on normal controller or database execution time.

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

## Internal Media follow-up command

`POST /api/v1/internal/media/follow-up-tasks` is not a public bearer endpoint. It accepts only
the `media-service` workload identity/token and an Identity-issued subject proof in the bounded
JSON body (`subjectId`, `sessionId`, `authenticationMethod`, `accessTokenProof`, `title`, optional
`priority` and `dueAt`). TaskGoal re-authorizes `task:create` using locally derived owner/tenant
facts before persisting. A valid `Idempotency-Key` is required; the existing Task idempotency
reservation returns the original task snapshot on matching retries. Missing/mismatched workload
credentials fail closed with `401`; malformed keys are `400`; authorization denial is generic `403`.
The endpoint is intended only for Media's explicit post-session confirmation flow and never accepts
raw user bearer tokens or caller-supplied owner/tenant fields.

## `POST /api/v1/goals`

Create a goal owned by the validated subject. The identity policy action is `goal:create`; it requires
both a permitted role and attributes showing the subject owns its personal tenant.

### Idempotency

Every create request must provide exactly one `Idempotency-Key` header. It is an opaque, case-sensitive
client-generated token matching `[A-Za-z0-9][A-Za-z0-9._~-]{0,127}`; in particular, it must be 1–128
characters, cannot contain whitespace, and is never trimmed or normalized. Generate a new key for a
new create intent.

The durable key scope is the validated `ownerAccountId` and `tenantId`, never data supplied by the
client. A same-key retry from the same subject with the same decoded `title` value returns the same
created resource (`201 Created` and its original `Location`) without inserting another goal. Its
`GoalResponse` is the resource's current versioned representation if a later lifecycle command has
changed it. Incidental JSON whitespace does not alter the decoded request value. Reusing the key in
that scope with a different title returns a generic `409 Conflict`; the response never reveals the
original goal or payload. The same raw key for a different validated account/tenant is a separate
operation.

The service authenticates and authorizes every retry before replaying it. It stores only
domain-separated SHA-256 digests of the idempotency key and request fingerprint alongside the original
goal identifier; raw keys and fingerprints are not logged. A committed reservation and its goal survive
process restarts. If an instance stops after reservation but before the goal transaction commits, a
matching authorized retry safely completes that same pre-allocated goal identifier.

### Request body

```json
{
  "title": "Land a Staff Engineer role",
  "priority": 0,
  "dueAt": "2026-09-30T17:00:00Z"
}
```

`title` must be non-blank and at most 255 characters. `priority` is optional and ranges from `0`
(critical) through `4` (low); it defaults to `3`. `dueAt` is an optional UTC instant. Owner and
tenant fields are intentionally absent from the public contract. Updates may set both planning
facts atomically with the title under the same ETag/idempotency contract.

### Responses

| Status | Condition | Body |
| --- | --- | --- |
| `201 Created` | Authenticated subject is allowed to create its personal goal, or retries the same key and decoded title | The created resource's `GoalResponse`, `Location: /api/v1/goals/{id}`, and a strong `ETag` for the returned version |
| `400 Bad Request` | Blank/overlong title, missing, duplicated, or malformed `Idempotency-Key` | Validation error body; values must not be treated as authorization facts |
| `409 Conflict` | Same account/tenant key is reused with a different title | `{ "error": "Idempotency key conflicts with an existing request" }` with no original goal details |
| `503 Service Unavailable` | The durable idempotency reservation is locked or temporarily unavailable | `{ "error": "Idempotency request is temporarily unavailable" }` plus `Retry-After: 1`; retry with the same key |
| `401`, `403`, `503` | Authorization-boundary failures | Generic body defined above |

## Goal lifecycle and version contract

Every `GoalResponse` carries an integer `version` and lifecycle facts:

```json
{
  "id": "aec92835-a053-4811-980c-f2a8e67a46c2",
  "title": "Land a Staff Engineer role",
  "status": "ACTIVE",
  "priority": 0,
  "dueAt": "2026-09-30T17:00:00Z",
  "version": 0,
  "createdAt": "2026-07-31T04:10:26.871896Z",
  "updatedAt": "2026-07-31T04:10:26.871896Z",
  "completedAt": null,
  "archivedAt": null
}
```

`POST /api/v1/goals`, `GET /api/v1/goals/{goalId}`, and every successful lifecycle mutation return
the strong `ETag` form `"<version>"`. Lifecycle writes require exactly one matching strong numeric
`If-Match` value. Weak tags (`W/`), `*`, comma-separated values, duplicate headers, non-numeric values,
and absent values are not accepted.

| Current status | `PUT` title update | `POST …/complete` | `POST …/archive` |
| --- | --- | --- | --- |
| `ACTIVE` | Allowed; remains `ACTIVE` | Allowed; becomes `COMPLETED` | Allowed; becomes `ARCHIVED` |
| `COMPLETED` | Rejected | Rejected | Allowed; becomes `ARCHIVED` |
| `ARCHIVED` | Rejected | Rejected | Rejected |

Archive is the terminal retention state for this story. There is intentionally no unarchive, reopen,
or hard-delete endpoint.

### Lifecycle idempotency

Each lifecycle command must provide exactly one `Idempotency-Key` using the same opaque 1–128 character
syntax as create. The durable key scope is `(validated actorAccountId, trusted target tenantId, goalId,
operation, key hash)`; the operation is one of `UPDATE`, `COMPLETE`, or `ARCHIVE`. The request
fingerprint additionally binds the goal ID, operation, expected version, decoded title, priority,
and deadline where
applicable. Therefore the same raw key may be used for a different command operation, but cannot be
reused for a different payload or expected version for the same scoped operation.

The service authenticates and authorizes every retry before looking up a reservation. A matching retry
returns the immutable response snapshot from the first committed command—even if a later command has
changed the goal and even though the original `If-Match` value is then stale. Reservations, snapshots,
and the lifecycle write commit durably together; a pending reservation left by a failed process is safely
resumed by a matching authorized retry. Raw idempotency keys and original request payloads are not
stored; the immutable response snapshot retains the title that was returned to the caller.

| Status | Lifecycle mutation condition | Body / retry behavior |
| --- | --- | --- |
| `200 OK` | Valid state transition or exact matching replay | Stored/current `GoalResponse` with the mutation response's `ETag` |
| `400 Bad Request` | Invalid/duplicate `Idempotency-Key` or malformed/duplicate `If-Match` | Generic validation body; no mutation is reserved |
| `428 Precondition Required` | `If-Match` absent | `{ "error": "If-Match is required for goal lifecycle mutations" }` |
| `409 Conflict` | Invalid state transition, or reused scoped key with a different fingerprint | Generic error; prior state is retained |
| `412 Precondition Failed` | A different scoped command has already advanced the goal version | `{ "error": "Goal representation is no longer current" }`; fetch the goal and submit a new command/key |
| `503 Service Unavailable` | Bounded reservation/row lock or persistence work cannot complete safely | `{ "error": "Idempotency request is temporarily unavailable" }` plus `Retry-After: 1`; retry the same request/key |
| `401`, `403`, `503` | Authorization-boundary failures | Generic body defined above; missing and cross-user target IDs remain indistinguishable |

## `PUT /api/v1/goals/{goalId}`

Rename an active goal after a `goal:update` object authorization decision. The complete mutable
representation is:

```json
{ "title": "Land a Staff Engineer role" }
```

`title` is required, non-blank, and at most 255 characters. Optional `priority` and `dueAt` update
planning facts atomically; a legacy title-only update preserves existing planning facts. Send `Idempotency-Key` and `If-Match`
according to the lifecycle contract. A successful response is `200 OK`, the updated `GoalResponse`,
and `ETag: "<new-version>"`.

## `POST /api/v1/goals/{goalId}/complete`

Complete an active goal after a `goal:complete` object authorization decision. It has no request body;
send `Idempotency-Key` and `If-Match` according to the lifecycle contract. A successful response is
`200 OK`, a `COMPLETED` `GoalResponse` with `completedAt`, and `ETag: "<new-version>"`.

## `POST /api/v1/goals/{goalId}/archive`

Archive an active or completed goal after a `goal:archive` object authorization decision. It has no
request body; send `Idempotency-Key` and `If-Match` according to the lifecycle contract. A successful
response is `200 OK`, an `ARCHIVED` `GoalResponse` with `archivedAt`, and `ETag: "<new-version>"`.

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
    "status": "ACTIVE",
    "version": 0,
    "createdAt": "2026-07-31T04:10:26.871896Z",
    "updatedAt": "2026-07-31T04:10:26.871896Z",
    "completedAt": null,
    "archivedAt": null
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
| `200 OK` | Authenticated and allowed | `GoalResponse` plus `ETag: "<version>"` |
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

## Task lifecycle

`/api/v1/tasks` is an owner-scoped personal-task API. A Task has `ACTIVE`, `COMPLETED`, or
terminal `CANCELED` state; only an active Task may be renamed, completed, or canceled. The service
uses v2 identity actions `task:create`, `task:list`, `task:read`, `task:update`, `task:complete`,
and `task:cancel`. Task owner and tenant are derived from the validated subject, never request JSON.

Every Task response contains `id`, `title`, `status`, `version`, `createdAt`, `updatedAt`,
`completedAt`, and `canceledAt`, and is returned with a strong `ETag: "<version>"`.

| Endpoint | Behavior |
| --- | --- |
| `POST /api/v1/tasks` | Creates an active Task. Body may include `{ "title": "Prepare launch", "priority": 1, "dueAt": "2026-09-01T17:00:00Z" }`; priority is `0..4` (default `3`), dueAt is optional; requires one `Idempotency-Key`; returns `201`, `Location`, and ETag. |
| `GET /api/v1/tasks` | Lists only the validated subject's Tasks in stable creation/ID order. |
| `GET /api/v1/tasks/{taskId}` | Reads one caller-owned Task; a missing and another user's ID receive the same generic `403`. |
| `PUT /api/v1/tasks/{taskId}` | Replaces an active Task title and optionally its priority/deadline; omitted planning fields preserve existing values; requires `If-Match` and `Idempotency-Key`. |
| `POST /api/v1/tasks/{taskId}/complete` | Completes an active Task; requires `If-Match` and `Idempotency-Key`. |
| `POST /api/v1/tasks/{taskId}/cancel` | Cancels an active Task; requires `If-Match` and `Idempotency-Key`. |

`Idempotency-Key` is exactly one opaque, case-sensitive 1–128 character value matching
`[A-Za-z0-9][A-Za-z0-9._~-]{0,127}`. The service stores only domain-separated SHA-256 digests.
Create retries are scoped to the authenticated owner/tenant; lifecycle retries are additionally
scoped to Task and operation. Matching retries return the original immutable command snapshot;
reusing the same scoped key for a different decoded request returns a generic `409` without
revealing the original Task. A separately committed create reservation recovers the original
preallocated Task ID after a process interruption.

Task updates, completion, and cancellation require exactly one strong numeric `If-Match` value such
as `"3"`; weak tags, `*`, comma-separated or duplicate headers, and missing values are rejected.
Absent preconditions return `428`; a stale representation returns `412`; an invalid state transition
or idempotency fingerprint conflict returns `409`; a bounded reservation/lock failure returns `503`
with `Retry-After: 1` and should be retried with the same key.

## Persisted Task/Goal dependencies and execution order

The persisted graph uses v2 actions `task:dependency-manage` and `task:dependency-order`. Both
actions authorize the caller's `task-dependency-graph` tenant collection; task-goal-service then
loads every endpoint from local owner/tenant-scoped repositories. This makes missing and cross-user
node IDs indistinguishable at the public boundary.

```text
PUT    /api/v1/dependencies/{dependentType}/{dependentId}/depends-on/{predecessorType}/{predecessorId}
DELETE /api/v1/dependencies/{dependentType}/{dependentId}/depends-on/{predecessorType}/{predecessorId}
GET    /api/v1/dependencies/execution-order
```

`dependentType` and `predecessorType` are exact `TASK` or `GOAL` values. The PUT persists
`predecessor -> dependent`; repeated identical PUTs and DELETEs are idempotent `204 No Content`
set operations. Both nodes must exist in the authenticated subject's scope. A self edge returns
`400`; a candidate cycle returns `409`; missing/cross-user nodes return the same generic `403` as
other protected resources; bounded graph persistence/locking failure returns `503` with
`Retry-After: 1`.

`GET /api/v1/dependencies/execution-order` returns every caller-owned persisted Task and Goal in a
complete dependency-respecting order:

```json
{
  "order": [
    { "type": "GOAL", "id": "aec92835-a053-4811-980c-f2a8e67a46c2" },
    { "type": "TASK", "id": "b9ed5ea7-9c3d-4b96-90e1-7887a03218bf" }
  ]
}
```

The service reads a deterministic owner-scoped projection, then delegates to the shared bounded
Kahn implementation. The traversal is O(V + E) time and O(V + E) memory after retrieval, permits
at most 10,000 nodes and 50,000 edge records, and never returns a partial order on a cycle or
oversize graph. A short per-owner graph guard serializes only dependency-edge mutations, preventing
two concurrent opposite edges from both committing a cycle; ordinary Task lifecycle writes are not
blocked by it.

`POST /api/v1/goals/dependency-order` remains an explicit compatibility endpoint for submitted
free-text labels only. It does not persist relationships and should not be used by new clients.

## Operational endpoints and configuration

The public application listener defaults to port `8082`. Actuator uses a separate loopback-only
listener at `http://127.0.0.1:9082` by default, configured by `TASK_GOAL_MANAGEMENT_ADDRESS` and
`TASK_GOAL_MANAGEMENT_PORT`. Put any non-loopback management exposure behind a deployment-managed
authenticated proxy; do not expose it directly through the gateway.

The enabled management endpoints are:

- `GET /actuator/health` — generic health status without dependency details.
- `GET /actuator/health/liveness` and `GET /actuator/health/readiness` — Kubernetes-style probes;
  readiness includes the database check.
- `GET /actuator/info` and `GET /actuator/prometheus` — service metadata and Prometheus metrics.

No datasource fallback is committed to source. Deployments must set non-blank
`TASK_GOAL_DATASOURCE_URL`, `TASK_GOAL_DATASOURCE_USERNAME`, and
`TASK_GOAL_DATASOURCE_PASSWORD`; omission or a blank value prevents startup rather than connect
with a shared credential. The stock local Compose initializer creates the `lifeos_task_goal`
database but no separate Task/Goal role, so the template derives both service credential values
from `LIFEOS_POSTGRES_USER` and `LIFEOS_POSTGRES_PASSWORD`. Use distinct service credentials only
after provisioning that role and database grants. See the
[observability runbook](../operations/observability.md) for the local reference telemetry stack and
its production boundaries.

## Data store

PostgreSQL database `lifeos_task_goal`, table `goal`:

| Column | Purpose |
| --- | --- |
| `id` | Application-generated goal UUID |
| `title` | Goal title |
| `created_at` | Creation timestamp |
| `updated_at` | Timestamp of the last lifecycle write |
| `status` | `ACTIVE`, `COMPLETED`, or terminal `ARCHIVED` |
| `completed_at` | Completion timestamp; present only after completion unless archived directly |
| `archived_at` | Archive timestamp; present only for terminal archived goals |
| `version` | Optimistic representation version returned in `ETag` |
| `owner_account_id` | Immutable authenticated owner for newly created rows |
| `tenant_id` | Immutable tenant scope derived from the owner subject |

The `(owner_account_id, tenant_id)` index supports owner-scoped list queries without a full table
scan. Flyway owns schema evolution and Hibernate runs with `ddl-auto: validate`; see the
[database migration runbook](../operations/database-migrations.md) for deployment and rollback
procedures. Existing ownerless rows are intentionally not backfilled because no safe ownership fact
exists; they remain fail-closed.

`goal_create_idempotency` is a second durable table with one reservation per successful goal create
or recoverable in-flight attempt. Its unique `(owner_account_id, tenant_id, idempotency_key_hash)`
constraint linearizes concurrent submissions across service instances; `goal_id` links the stable
replay response to the one persisted goal. The table contains SHA-256 digests rather than plaintext
client keys or request bodies, and its goal lookup index keeps replay recovery bounded.

`goal_mutation_idempotency` stores update, complete, and archive reservations separately from create.
Its unique `(actor_account_id, tenant_id, goal_id, operation, idempotency_key_hash)` constraint scopes
one retry intent, while its immutable result columns preserve the original mutation response for replay.
The `goal_id` index bounds target-oriented diagnostics and retention work. A database state check ensures
that pending reservations have no result snapshot and completed reservations have a complete snapshot.

`task` has the same immutable owner/tenant and versioned-representation shape as `goal`, with
`CANCELED` rather than `ARCHIVED` as its second terminal state. Its indexed
`(owner_account_id, tenant_id, created_at, id)` projection makes owner-scoped listing and stable
execution-order tie-breaking bounded without a full table scan.

`task_command_idempotency` combines Task create and lifecycle reservations. Its unique
`(actor_account_id, tenant_id, operation, target_scope, idempotency_key_hash)` key scopes one exact
retry intent; `target_scope=create` makes a create reservation recoverable even though its preallocated
Task ID is not known to a later retry. The stored result snapshot is immutable and contains no raw
client key or request fingerprint.

`task_goal_dependency` stores only scoped, directed `TASK`/`GOAL` identifiers. Database constraints
reject duplicate, invalid-type, and self edges; application checks establish polymorphic node
existence and ownership before commit. `task_goal_dependency_guard` contains one lockable row per
owner/tenant graph, limiting serialized work to concurrent edge mutations for that one personal
 planning graph.

## Planning APIs

Habits, routines, milestones, and recurring materialization use the same bearer, owner/tenant,
strong `If-Match`, and durable `Idempotency-Key` rules as Goal lifecycle writes. Cross-owner and
missing identifiers return the same generic not-found response. Trend and materialization windows
are bounded to 366 days.

### Habits

- `POST /api/v1/habits` creates a daily or weekly habit (`name`, `cadence`, optional IANA
  `timeZone`) and requires `Idempotency-Key`.
- `GET /api/v1/habits` and `GET /api/v1/habits/{habitId}` return only the caller's habits.
- `PUT /api/v1/habits/{habitId}` requires `If-Match` and `Idempotency-Key`.
- `POST /api/v1/habits/{habitId}/occurrences` records one bounded calendar occurrence and is
  duplicate-idempotent.
- `GET /api/v1/habits/{habitId}/trend?from=YYYY-MM-DD&to=YYYY-MM-DD` returns deterministic
  completion counts and streaks.

### Routines and recurring activities

- `POST /api/v1/routines` creates a versioned daily, weekly, or monthly ordered activity list.
- `GET /api/v1/routines`, `GET /api/v1/routines/{routineId}`, and `PUT /api/v1/routines/{routineId}`
  provide owner-scoped CRUD; updates require strong `If-Match` and `Idempotency-Key`.
- `POST /api/v1/routines/{routineId}/materialize` accepts a bounded `from`/`to` window and
  idempotently materializes cadence occurrences without an unbounded background job.

### Goal milestones

- `POST /api/v1/goals/{goalId}/milestones` creates an ordered milestone (`title`, optional
  `criteria`, `position`) and requires `Idempotency-Key`.
- `GET /api/v1/goals/{goalId}/milestones` lists milestones in deterministic position/ID order.
- `GET /api/v1/milestones/{milestoneId}`, `PUT /api/v1/milestones/{milestoneId}`, and
  `POST /api/v1/milestones/{milestoneId}/complete` read or mutate a milestone; mutations require
  the strong `If-Match` version and `Idempotency-Key`.

## Internal gRPC metrics host

The service contains an opt-in `lifeos.grpc.v1.TaskMetricsService/GetMetrics` host for bounded
GraphQL/dashboard aggregation. It is disabled by default and listens on `TASK_GOAL_GRPC_PORT`
(`10082` by default) only when `TASK_GOAL_GRPC_ENABLED=true`. Enabling it requires
`TASK_GOAL_GRPC_TLS_ENABLED=true`, a server certificate/private key/trusted client CA, and the
deployment-owned `TASK_GOAL_GRPC_WORKLOAD_TOKEN`. Calls must provide that token as
`x-lifeos-workload-token` over mutual TLS; invalid account UUIDs, tenants, or periods outside
1–90 days receive `INVALID_ARGUMENT`. The response contains only bounded owner/tenant Task counts
and an observation timestamp. No bearer token or private content is carried in the protobuf.
