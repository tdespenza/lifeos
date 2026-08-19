# calendar-service API and reminder producer contract

Gateway base URL after the deployment route batch: `http://localhost:8080`

Direct local service URL: `http://localhost:8085`

Loopback management URL: `http://localhost:9085`

`calendar-service` owns calendar events, focus blocks, recurrence materialization, reminder templates and due leases, the Calendar producer outbox/dead letter, idempotency snapshots, and redacted audit facts. It does not own notification endpoints/delivery, Task/Goal ownership, or a shared database.

## Authentication, authorization, and errors

All public routes require `Authorization: Bearer <access-token>`. Calendar validates it with Identity using the `calendar-service` workload identity, requests Identity policy v2 actions, and rechecks its local immutable owner/tenant facts. The current tenant is the authenticated account's UUID string; request bodies never select an owner or tenant.

| Status | Meaning |
| --- | --- |
| `400` | Invalid JSON, input, strong ETag, idempotency key, date range, or bounded list value. |
| `401` | Missing/invalid bearer credential. |
| `403` | Identity denied the requested Calendar capability. |
| `404` | No locally accessible Calendar resource exists. |
| `409` | Conflicting `Idempotency-Key` reuse, invalid lifecycle transition, or schedule overlap. Conflict bodies contain only interval facts. |
| `412` | The supplied `If-Match` version is stale. |
| `422` | A Task/Goal link is unsupported or a conflict window exceeds the 500-commitment safety limit. |
| `428` | An update/cancel omitted `If-Match`. |
| `503` | Identity, audit, or durable idempotency dependency is unavailable; callers may retry. |

Calendar writes a redacted durable audit record for completed event/block mutations and locally confirmed unsupported-link/conflict rejections. It stores actor/session IDs, target identifiers, safe action/outcome/reason codes, correlation ID, and a keyed client-address digest only—never calendar text, location, raw IP, bearer, or idempotency key.

## Event lifecycle

### `POST /api/v1/calendar/events`

Required headers:

```http
Authorization: Bearer <access-token>
Idempotency-Key: <16-128 character opaque key>
```

```json
{
  "title": "Planning block",
  "description": "Optional private text",
  "startAt": "2026-08-18T16:00:00Z",
  "endAt": "2026-08-18T17:00:00Z",
  "timeZone": "America/Chicago",
  "recurrence": {"frequency": "WEEKLY", "interval": 1, "count": 8},
  "reminders": [{"minutesBefore": 15, "requestedChannels": ["EMAIL", "PUSH", "REALTIME"]}]
}
```

Returns `201 Created`, `Location`, and `ETag: "0"`. A matching retry returns the exact original status/body/location plus `Idempotent-Replay: true`; a changed request with an already completed key is `409`.

### Read, update, and cancel

- `GET /api/v1/calendar/events?limit={1..200}` returns the first deterministic owner page sorted by `startAt,id`. The SQL query receives the bound; it does not load an unbounded history. Cursor pagination is a follow-up compatibility addition.
- `GET /api/v1/calendar/events/{eventId}` returns the owner-scoped representation and `ETag`.
- `PUT /api/v1/calendar/events/{eventId}` uses the create body and requires `Idempotency-Key` plus strong `If-Match: "<version>"`. It replaces future recurrence/reminder work under a new recurrence revision; published notifications cannot be recalled.
- `POST /api/v1/calendar/events/{eventId}/cancel` requires both headers. It cancels future occurrences, scheduled/leased reminders, and pending local outbox work without deleting audit/history rows.

## Time blocks, conflicts, and suggestions

`POST`, `GET`, `GET /{blockId}`, `PUT /{blockId}`, and `POST /{blockId}/cancel` are available at `/api/v1/calendar/time-blocks`. Create returns `201`; updates/cancel require `If-Match`; every mutation requires `Idempotency-Key`.

Only this safe focus form is currently accepted:

```json
{
  "linkType": "FOCUS",
  "linkedResourceId": null,
  "startAt": "2026-08-18T18:00:00Z",
  "endAt": "2026-08-18T19:00:00Z",
  "timeZone": "America/Chicago"
}
```

`TASK` and `GOAL` are verified through TaskGoal's workload-authenticated, Identity-v2-reauthorized ownership projection before persistence. If the projection credential is absent, unavailable, or denies the resource, Calendar returns the same generic `422`. Calendar never trusts a client-supplied owner fact, forwards a client bearer, or performs a cross-service database read.

### `GET /api/v1/calendar/conflicts?from={instant}&to={instant}`

Returns deterministic event-occurrence/time-block conflicts for the caller. Intervals are half-open, so an entry ending exactly when another begins is not a conflict. `from..to` is capped at 90 days and 500 returned commitments to bound query/memory work.

### `POST /api/v1/calendar/optimization-suggestions`

```json
{
  "from": "2026-08-18T00:00:00Z",
  "to": "2026-08-20T00:00:00Z",
  "minimumFocusMinutes": 30,
  "maxSuggestions": 5,
  "candidates": [
    { "linkType": "TASK", "resourceId": "00000000-0000-0000-0000-000000000001", "focusMinutes": 45 }
  ]
}
```

Returns bounded, deterministic, non-mutating suggestions. With explicit candidates, Calendar calls
TaskGoal's workload-authenticated `/api/v1/internal/planning/priority-projection` for each candidate,
then ranks by priority (`0` first), deadline, and UUID while allocating only available focus windows.
The response never includes task/goal titles or ownership facts. If candidates are omitted or the
projection is unavailable, `degradedSources` contains `task-goal` and Calendar returns the safe
free-focus fallback; linked-block ownership itself is verified through the narrow projection above.

## Recurrence and reminders

Recurrence accepts bounded local-civil rules: `DAILY`, `WEEKLY`, or `MONTHLY`, interval `1..365`, and count `1..1000`. Calendar persists normalized UTC instants and an IANA time zone. The scheduler materializes only future occurrences inside its configurable 30-day/100-occurrence default horizon; it never performs unbounded historical catch-up.

Each event has at most five unique reminder offsets (`0..10080` minutes) and selected channels. When due, an owner-scoped lease plus `FOR UPDATE SKIP LOCKED` atomically creates one Calendar outbox record. A late reminder more than 60 seconds behind is expired instead of emitting a stale notification. The Kafka relay has a bounded virtual-thread publish pool, idempotent producer, five-second send timeout, capped full-jitter retry, and a local `calendar_outbox_dead_letter` row after the configured attempt cap.

## Versioned Notification producer contract

Calendar is the first implemented source for the notification foundation. It publishes a CloudEvents 1.0 `NotificationRequestedV2` record to:

| Field | Value |
| --- | --- |
| Type | `com.lifeos.notification.requested.v2` |
| Topic | `lifeos.notification.requested.v2` |
| Kafka key | Authenticated recipient account UUID |
| Source | `urn:lifeos:calendar-service` |
| Subject | `notification/{producer-stable-notification-id}` |
| Correlation | The Calendar mutation correlation UUID |
| Extra V2 field | Canonical IANA `eventTimeZone` |

The source-outbox payload is deliberately generic:

```json
{
  "category": "calendar.reminder",
  "title": "Calendar reminder",
  "body": "An upcoming calendar event is starting soon.",
  "actionUri": "lifeos://calendar/events/{eventId}"
}
```

No event title, description, location, invitee, task/goal name, endpoint, token, or raw user preference enters this contract. `notification-service` retains V1 support unchanged and consumes V2 via a distinct listener/topic; it stores the V1-compatible delivery representation plus the time-zone fact. A broker acknowledgement may be duplicated after a crash, so Notification's inbox deduplicates the CloudEvents ID. Calendar does not claim that Goal lifecycle events create a notification.

## Deployment and migration prerequisites

`calendar-service` listens on `8085`; its health/probe/Prometheus management listener is loopback `9085`. Required configuration includes `CALENDAR_DATASOURCE_URL`, `CALENDAR_DATASOURCE_USERNAME`, `CALENDAR_DATASOURCE_PASSWORD`, `CALENDAR_IDEMPOTENCY_SECRET`, `CALENDAR_AUDIT_CLIENT_FINGERPRINT_SECRET`, and `IDENTITY_CALENDAR_WORKLOAD_TOKEN`. Kafka deployment must configure `LIFEOS_KAFKA_BOOTSTRAP_SERVERS` and topic ACLs for Calendar's V2 producer. The Calendar outbox/dead-letter retention policy is a separate local operational obligation. The production image is `infrastructure/docker/calendar-service.Dockerfile` and runs as non-root.

Flyway V1 creates the Calendar schema; V2 adds the recurrence-scan scheduling index/column. The production PostgreSQL path uses these migrations; H2 equivalents are test-only. Gateway routing and Compose database/secret wiring are present; production Kafka topic provisioning, provider vendor deployment, outbox retention, and staging evidence remain deployment work. The TaskGoal ownership projection is implemented at `/api/v1/internal/planning/ownership-proof`; provision `IDENTITY_CALENDAR_WORKLOAD_TOKEN` in both services to enable it.
