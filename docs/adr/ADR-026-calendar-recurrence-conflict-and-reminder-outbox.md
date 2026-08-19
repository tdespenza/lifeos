# ADR-026: Calendar recurrence, conflict serialization, and privacy-safe reminder production

## Status

Accepted — implemented in `calendar-service`; gateway routing and Compose database/secret wiring are integrated. Kafka topic ACLs, provider deployment, and staging remain operator-owned.

## Context

Calendar events, focus blocks, recurrence, and reminders each cross correctness boundaries: concurrent conflicting time-block writes must not both succeed, a scheduler must not generate an unbounded historical backlog, and a reminder must survive a Calendar crash or Kafka outage without placing private event content into a broker record. The Notification V1 contract has no explicit event time zone, while FR28 requires that fact to travel with a reminder command.

## Decision

- `calendar-service` owns PostgreSQL calendar events, materialized occurrences, reminder templates, reminders, outbox events/dead letters, owner schedule guards, idempotency snapshots, and redacted audit facts. Hibernate validates; Flyway V1 creates the service schema and V2 adds the recurrence scan schedule. H2 has matching migrations for mandatory local tests.
- Every public mutation has a bounded `Idempotency-Key`; updates and cancellation also require a strong numeric `If-Match` ETag. A reservation commits independently, while a successful response snapshot commits atomically with the domain write. Deterministically rejected mutations discard the pending reservation, because no business effect exists for a later retry to replay.
- Calendar validates an Identity v2 `calendar:*` action for each owner-scoped operation and then rechecks local immutable owner/tenant facts. Calendar's current personal tenant is exactly the account UUID string. It never accepts ownership from a request body.
- A per-owner `calendar_schedule_lock` row is created in a short independent transaction and then locked inside the conflict/write transaction. This prevents two first writes from racing the guard creation and serializes only one user's conflicting time-block operations, not all Calendar users.
- Intervals are half-open (`start < otherEnd && end > otherStart`), so adjacent blocks are not conflicts. Indexed queries bound a request to 90 days and at most 500 returned commitments; a denser range is rejected instead of materializing an unbounded result.
- Recurrence is a bounded local-civil-time rule (`DAILY`, `WEEKLY`, or `MONTHLY`, interval 1–365, count 1–1000). The base occurrence is durable at event creation; each series lock materializes only unmaterialized occurrences in the current-to-horizon window. The recurrence due timestamp prevents a fixed first page from starving other series across scheduler runs. Expansion performs O(count) bounded work and stores no past catch-up occurrences.
- A due reminder is claimed using `FOR UPDATE SKIP LOCKED`, given a lease, transformed into one outbox row in the same transaction, and marked outboxed. The relay claims at most its bounded virtual-thread publish capacity, uses an idempotent Kafka producer with a five-second deadline, retry/full-jitter backoff, and local dead letter after the configured attempt limit.
- Calendar publishes `NotificationRequestedV2` rather than changing V1. V2 uses CloudEvents type `com.lifeos.notification.requested.v2`, topic `lifeos.notification.requested.v2`, recipient UUID as Kafka key, a producer-stable event ID, correlation ID, and IANA event time zone. The notification consumer validates V2 on its separate listener, persists its V1-compatible delivery model plus the time-zone fact, and keeps V1 unchanged.
- The Calendar producer always emits `Calendar reminder` / `An upcoming calendar event is starting soon.` and a `lifeos://calendar/events/{id}` action. It does not put an event title, description, location, invitee, Task/Goal title, endpoint, bearer token, or user preference into Kafka, logs, metrics, or its dead-letter fact.

## Consequences

- A process crash after broker acknowledgement can produce a duplicate Kafka record; the Notification durable inbox deduplicates the CloudEvents ID. Kafka ACLs, retention, topic provisioning, and operator DLT replay remain deployment obligations.
- A reminder more than 60 seconds late is expired rather than producing an arbitrarily stale alert. The scheduler does not block Calendar HTTP reads/writes during broker/provider outage.
- Calendar accepts `TASK` and `GOAL` blocks only after calling TaskGoal's workload-authenticated `/api/v1/internal/planning/ownership-proof` projection. TaskGoal receives the already validated subject proof, reloads local facts, reauthorizes `task:read` / `goal:read` through Identity v2, and returns only `204` or a generic no-disclosure denial. Calendar must not forward a client bearer, query a TaskGoal database, or persist copied task/goal ownership details. Missing deployment credentials keep the path fail closed.
- Local optimization remains non-mutating and bounded. With explicit Task/Goal candidate IDs it
  obtains priority/deadline facts through a separate workload-authenticated projection, ranks by
  priority/deadline/UUID, and allocates only free windows. Missing candidates or an unavailable
  projection returns the deterministic free-focus fallback and reports `task-goal` degraded; no
  ownership or title facts are copied into Calendar.

## Verification

H2 tests cover API ETags/idempotency, owner-scoped lifecycle paths, half-open conflict behavior, first-write concurrency, recurrence bounds, privacy-safe V2 payloads, reminder/outbox idempotency, and producer dead lettering. `CalendarPostgresReminderLeaseIntegrationTest` uses PostgreSQL 17 Testcontainers to prove concurrent schedulers create one outbox event. Event-contract and notification tests prove V2 serialization, dedicated consumer routing, durable inbox handling, and time-zone persistence.
