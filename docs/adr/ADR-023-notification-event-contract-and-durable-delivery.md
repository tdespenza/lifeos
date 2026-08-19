# ADR-023: Versioned CloudEvents contracts and durable notification delivery

## Status

Accepted — notification-service foundation and Calendar's V2 reminder producer path are implemented.

## Context

LifeOS needs one notification service that can accept a valid asynchronous request, fan it out to email, push, and realtime channels independently, and retain correct state through duplicate Kafka delivery, provider outages, and process crashes. A provider address or device token is more sensitive than the event payload and must not be placed in a producer event, broker key, metric, or log.

ADR-016 selects Kafka as the default backbone and ADR-017 selects a transactional outbox. This ADR fixes the notification-specific contract, privacy boundary, durable state, and recovery behavior.

## Decision

`contracts:event-contracts` provides immutable Java/JSON contracts under `com.lifeos.events.v1`:

- `CloudEventV1<T>` is a CloudEvents 1.0 JSON envelope with immutable UUID `id`, source, type, subject, timestamp, `application/json` data content type, and LifeOS `correlationId` extension.
- `NotificationRequestedV1` uses type `com.lifeos.notification.requested.v1` and topic `lifeos.notification.requested.v1`. Its Kafka key is `recipientAccountId`, not event ID, so one recipient's records stay ordered within a partition. The producer preserves event UUID on retry; notification-service uses it as durable inbox dedupe identity.
- `NotificationRequestedV2` uses type `com.lifeos.notification.requested.v2` and topic `lifeos.notification.requested.v2`. It adds a canonical IANA `eventTimeZone` without changing V1. Notification consumes V2 through its own listener, uses the same durable inbox collision rules, and persists the V1-compatible delivery fields plus that time-zone fact.
- `NotificationDeliveryStatusV1` uses type `com.lifeos.notification.delivery-status.v1` and topic `lifeos.notification.delivery-status.v1`. It has only outcome metadata, never endpoint or rendered content.

The requested payload contains a recipient account, tenant scope, bounded category/priority/title/body/action, selected channels, and optional expiry. It contains no email address, push token, cookie, bearer token, or active stream identity. Notification-service resolves encrypted, account-owned endpoints locally. LifeOS currently has personal one-account tenancy, so the consumer rejects a request whose `tenantId` is not exactly the recipient account UUID; a future shared-tenant model must replace that invariant with explicit membership/producer authorization.

`notification-service` implements:

1. A PostgreSQL transaction that reserves `notification_inbox_event.event_id`, creates one recipient-sequenced notification record, creates independent delivery work items, and marks the inbox row processed. The unique event ID prevents duplicate business effects during at-least-once Kafka delivery. Reuse of an ID with a different payload hash is rejected.
2. A bounded lease worker. It claims due work with `FOR UPDATE SKIP LOCKED`, releases the transaction before an external provider call, then records `DELIVERED`, `RETRY_SCHEDULED`, `SKIPPED`, or `DEAD_LETTERED` with capped exponential full jitter. Push invalid-destination failures disable only that endpoint idempotently. Email/push calls carry a derived idempotency key and explicit deadline.
3. A database `notification_dead_letter` record for permanent/exhausted provider work. It retains only source event ID, channel, safe reason code, attempt count, and payload hash.
4. A `notification_outbox_event` written in the same transaction as each delivery outcome. A Kafka relay leases rows with `SKIP LOCKED`, sends with `acks=all` and idempotent producer settings, then marks them published. A crash after broker acknowledgement can republish, so consumers dedupe CloudEvents IDs. Broker failures remain pending with capped retry rather than discarded.
5. A bounded Kafka consumer policy: malformed contract records and conflicting event IDs go to `<source-topic>.DLT`; other consumer failures retry twice at one-second intervals before that durable Kafka DLT. This ingress DLT is separate from the local provider delivery dead-letter table.
6. Authenticated recipient-only notification history and SSE. Every account has a monotonic sequence; each account has a default cap of three streams, each with a fixed 100-event queue, heartbeat, timeout, and `Last-Event-ID` replay limit. Overflow or a sequence gap closes the stream; clients use paginated REST history to resynchronize. A delivery-status topic consumer runs once per service instance with a group derived from mandatory instance identity (`NOTIFICATION_INSTANCE_ID` or pod `HOSTNAME`), so every replica receives durable status fanout and publishes only to its local bounded SSE hub rather than silently load-balancing streams.
7. A privacy-minimized local audit fact records endpoint enrollment/revocation and authentication validation outcome without plaintext endpoint destinations, provider responses, bearer tokens, or request bodies. Push rendering is generic by default (`LifeOS notification` / `Open LifeOS to view this notification.`), so sensitive category/title/body data never becomes a lock-screen preview.

The public API validates a bearer token through identity-service with a workload credential, then uses the returned account ID to scope data locally. No new cross-domain identity action is needed: there is no caller-controlled account ID and every endpoint/history lookup includes its validated owner.

## Consequences

- Producers must serialize the exact versioned contract and preserve CloudEvents ID across retries.
- A real provider is deployment configuration, not a source-controlled mock. The HTTP adapter permits HTTPS origins except loopback development, requires an injected token, has explicit connect/read deadlines, and never logs response text.
- Endpoint enrollment has owner-scoped durable `Idempotency-Key` reservation. Email/push destinations are AES-256-GCM encrypted and HMAC-digested with distinct secrets.
- The realtime consumer requires an instance-specific identity when enabled. It derives the Kafka group itself rather than accepting a shared group-name override; a missing or unsafe identity fails startup before replicas can lose fanout.
- Kafka DLT topics require ACLs, retention, alerting, and operator replay controls before production exposure. Published outbox rows need a retention/pruning job before long-lived use; the foundation deliberately never deletes them automatically.

## Producer boundary

Calendar now produces only its due reminder commands through its own durable outbox using `NotificationRequestedV2`. Its source content is generic and contains no event title, description, location, invitee, or notification endpoint. Task/Goal does not emit a notification request, and no documentation should imply goal completion notifies a user. Other producer semantics remain a product decision rather than an implication of this shared contract.

## Validation

The module has contract/unit tests, real H2 inbox/migration tests, and an opt-in PostgreSQL Testcontainers migration test when Docker is available. Calendar tests create privacy-safe V2 outbox records, while notification tests prove V2 listener/inbox handling and time-zone persistence. The required next end-to-end validation is a Calendar-produced Kafka record through a configured staging provider and a consumer replay showing one notification/delivery set for repeated CloudEvents IDs.
