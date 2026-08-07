# task-goal-service API

Base URL (local): `http://localhost:8082`

Status: goal CRUD (create/list) plus a real graph-algorithm endpoint for dependency ordering. Tasks, habits, routines, and milestones from the Task and Goal Service scope in `REQUIREMENTS.md` are not modeled yet — only `Goal` exists so far.

## `POST /api/v1/goals`

Create a goal.

### Request Body

```json
{ "title": "Land a Staff Engineer role" }
```

`title` must be non-blank (enforced on [`CreateGoalRequest`](../../services/task-goal-service/src/main/java/com/lifeos/taskgoal/goal/dto/CreateGoalRequest.java)).

### Responses

| Status | Condition | Body |
| --- | --- | --- |
| `201 Created` | Goal created | `GoalResponse`, `Location` header set to `/api/v1/goals/{id}` |
| `400 Bad Request` | Blank title | Spring's default validation error body |

## `GET /api/v1/goals`

List all goals.

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

## `POST /api/v1/goals/dependency-order`

Resolve a valid execution order for a set of goals given their dependencies, via topological sort. See [`docs/algorithms/topological-sort-goal-dependencies.md`](../algorithms/topological-sort-goal-dependencies.md) for the algorithm itself.

### Request Body

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

`goals` must be non-empty (see [`DependencyOrderRequest`](../../services/task-goal-service/src/main/java/com/lifeos/taskgoal/goal/dto/DependencyOrderRequest.java)); `dependencies` may be omitted or empty. Any goal name that appears only inside `dependencies` (not in the `goals` list) is still accepted and included in the returned `order` — the algorithm collects every node it sees across both inputs (see [`TopologicalSortService`](../../services/task-goal-service/src/main/java/com/lifeos/taskgoal/goal/algorithm/TopologicalSortService.java)).

### Responses

| Status | Condition | Body |
| --- | --- | --- |
| `200 OK` | Valid DAG | `{ "order": [...] }`, a valid topological order |
| `409 Conflict` | Dependencies contain a cycle | Plain-text message naming the unresolved goals |

**Example response (200)** for the request above:

```json
{ "order": ["Learn DSA", "Build Portfolio Project", "System Design Practice", "Apply to FAANG"] }
```

## `GET /actuator/health`

Same as [identity-service](identity-service.md#get-actuatorhealth) — only `health` and `info` are exposed.

## Data store

PostgreSQL, database `lifeos_task_goal`, table `goal` (id, title, createdAt). See [`Goal`](../../services/task-goal-service/src/main/java/com/lifeos/taskgoal/goal/Goal.java). Same `ddl-auto: update` caveat as identity-service applies here.
