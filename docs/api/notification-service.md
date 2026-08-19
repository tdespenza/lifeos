# notification-service API and event contract

Base URL through the gateway: `http://localhost:8080`

Service URL (local direct development): `http://localhost:8084`

Management URL (loopback by default): `http://localhost:9084`

`notification-service` owns recipient-scoped notification history, encrypted email/push endpoints, channel delivery state, a provider dead-letter table, and its own transactional outbox. It does not own Calendar reminder generation and it does not infer notifications from Goal events.

Every direct-service connector applies the configured `NOTIFICATION_INBOUND_REQUEST_TIMEOUT` (30
seconds by default) to connection, keep-alive, and upload reads. This bounds slow clients without
changing the separate 30-minute maximum lifetime of an admitted SSE stream.

## Authentication and ownership

All public endpoints require `Authorization: Bearer <access-token>`. The service validates the token through identity-service using its workload credential, then scopes every lookup to the validated account. There is no public account-ID path parameter or body field, so another account's endpoints, notification history, and SSE stream cannot be selected by a caller.

Endpoint enrollment/revocation and authentication validation write redacted durable audit facts. Audit records contain actor/session IDs, operation/outcome, correlation ID, and a safe reason code only; they never contain endpoint plaintext/ciphertext, provider response, token, or request body.

| Status | Code | Meaning |
| --- | --- | --- |
| `400` | `INVALID_NOTIFICATION_REQUEST` | Invalid cursor, endpoint input, or idempotency key |
| `401` | `AUTHENTICATION_REQUIRED` | Missing or invalid bearer credential |
| `404` | `NOTIFICATION_ENDPOINT_NOT_FOUND` | Missing or cross-owner endpoint |
| `409` | `IDEMPOTENCY_CONFLICT` | A retry key was reused with a different endpoint request |
| `409` | `STREAM_RESYNC_REQUIRED` | Bounded SSE replay cannot safely catch up; use history |
| `429` | `STREAM_CAPACITY_EXCEEDED` | Per-account stream cap is reached |
| `503` | `AUTHENTICATION_UNAVAILABLE` / `IDEMPOTENCY_UNAVAILABLE` | Fail-closed dependency or replay state |

## Notification history and realtime stream

### `GET /api/v1/notifications?after={sequence}&limit={1..100}`

Returns only the authenticated recipient's ascending cursor page:

```json
{
  "items": [
    {
      "id": "61c7d1d2-3365-4ef1-8a9a-b6e4f346a455",
      "sequence": 42,
      "category": "calendar.reminder",
      "priority": "NORMAL",
      "title": "Reminder",
      "body": "Your event starts soon.",
      "actionUri": "lifeos://calendar/events/123",
      "createdAt": "2026-08-17T12:00:00Z",
      "expiresAt": null
    }
  ],
  "nextCursor": 42
}
```

### `GET /api/v1/notifications/stream`

Produces `text/event-stream`. Send `Last-Event-ID: <sequence>` to resume after the last delivered notification. Each notification uses event name `notification` and its recipient-local sequence as the SSE ID. Heartbeats are comments. The service has bounded per-account connections, queue length, replay size, and connection timeout. A gap, queue overflow, or replay beyond the limit terminates the stream; the client reads history and reconnects with its latest cursor. This initial slice uses SSE rather than WebSocket.

The public gateway forwards only this exact `GET` stream operation as a bounded byte relay. It
preserves `Last-Event-ID`, gateway authentication/rate-limit/correlation controls, and safe SSE
headers, but does not buffer the event body or retry a live connection. Gateway-wide stream
admission and a finite 30-minute default read lifetime are independent from the notification
service's recipient-local stream cap; a client receiving a close or timeout reconnects through the
gateway with its last cursor. Notification history and endpoint operations continue through the
gateway's ordinary bounded buffered proxy.

## Encrypted email/push endpoint enrollment

### `POST /api/v1/notification-endpoints`

Required headers:

```http
Authorization: Bearer <access-token>
Idempotency-Key: <16-255 character opaque key>
```

Request:

```json
{
  "channel": "EMAIL",
  "destination": "person@example.test"
}
```

`channel` may be `EMAIL` or `PUSH`; `REALTIME` has no stored destination. The service normalizes the destination, AES-256-GCM encrypts it, retains an HMAC digest for uniqueness, and returns only metadata — never ciphertext or plaintext. The same account/key/request replays the same `201 Created` resource; reuse with different material is `409`.

Push delivery renders a generic lock-screen-safe title/body by default (`LifeOS notification` / `Open LifeOS to view this notification.`) and omits the deep link. Notification title, body, category, and action are never sent as a push preview, including for document, finance, and health events.

### `GET /api/v1/notification-endpoints`

Lists only the authenticated user's endpoint metadata, including a disabled endpoint's safe reason.

### `DELETE /api/v1/notification-endpoints/{endpointId}`

Idempotently disables an endpoint owned by the authenticated user and returns `204`. Missing and cross-owner identifiers have the same `404` shape. A confirmed permanent invalid push destination is also disabled idempotently by the delivery worker.

## Versioned Kafka contract

`contracts:event-contracts` defines a CloudEvents 1.0 JSON envelope. A producer sends `NotificationRequestedV1` to topic `lifeos.notification.requested.v1` with Kafka key equal to `recipientAccountId` and preserves the UUID CloudEvents `id` during retries.

```json
{
  "id": "c1a15d26-4ac5-4ea3-bc02-a8e030e3b017",
  "specversion": "1.0",
  "source": "urn:lifeos:calendar-service",
  "type": "com.lifeos.notification.requested.v1",
  "subject": "notification/61c7d1d2-3365-4ef1-8a9a-b6e4f346a455",
  "time": "2026-08-17T12:00:00Z",
  "datacontenttype": "application/json",
  "correlationId": "225061a5-f809-46c4-b3c4-c23a10ba220e",
  "data": {
    "notificationId": "61c7d1d2-3365-4ef1-8a9a-b6e4f346a455",
    "recipientAccountId": "d987248d-65d8-4c47-8012-22bd03401e78",
    "tenantId": "d987248d-65d8-4c47-8012-22bd03401e78",
    "category": "calendar.reminder",
    "priority": "NORMAL",
    "title": "Reminder",
    "body": "Your event starts soon.",
    "actionUri": "lifeos://calendar/events/123",
    "requestedChannels": ["EMAIL", "PUSH", "REALTIME"],
    "expiresAt": "2026-08-17T12:15:00Z"
  }
}
```

No email address, device token, bearer token, or connection ID belongs in this event. The consumer creates a durable inbox row by CloudEvents ID and acknowledges the same payload safely on replay; an altered payload reusing the ID is a poison record. Malformed/conflicting records go to `lifeos.notification.requested.v1.DLT` after the bounded policy.

For the current personal-tenancy model, `tenantId` must exactly equal `recipientAccountId` as a UUID string; a mismatch is rejected before inbox/domain state is written. The Kafka record key must also equal `recipientAccountId`, preserving recipient order and preventing a malformed producer record from bypassing that scope invariant.

Calendar's first real producer uses the additive `NotificationRequestedV2` type/topic `com.lifeos.notification.requested.v2` / `lifeos.notification.requested.v2`. V2 includes a canonical `eventTimeZone`; the distinct listener validates it, applies the same inbox dedupe and tenant/key checks, and persists that non-sensitive time-zone fact while V1 remains unchanged. Calendar's source content is generic (`Calendar reminder` / `An upcoming calendar event is starting soon.`), never a private event title, body, or location.

Channel outcomes are emitted through the notification-owned transactional outbox as `com.lifeos.notification.delivery-status.v1` on `lifeos.notification.delivery-status.v1`. Its key is also recipient account ID and its body contains only safe outcome metadata.

## Reliability and operations

- Provider calls happen outside database transactions, with leases and explicit deadline.
- Transient provider failures use max five attempts by default with capped exponential full jitter (`1s` initial, `5m` cap); a provider request carries `sourceEventId:deliveryId` as its idempotency key.
- Permanent/exhausted delivery is retained in `notification_dead_letter` with no raw destination or provider response. Broker poison records use the separate Kafka `.DLT` topic.
- `notification_outbox_event` uses `FOR UPDATE SKIP LOCKED`; Kafka publishes use `acks=all` and idempotent producer settings. Monitor `notification.delivery.outcomes`, `notification.outbox.relay.outcomes`, DLT depth, oldest pending outbox age, and endpoint-disable rates.
- Required startup secrets: `NOTIFICATION_DATASOURCE_*`, `NOTIFICATION_ENDPOINT_ENCRYPTION_KEY` (base64 32-byte AES key), `NOTIFICATION_IDEMPOTENCY_SECRET` (minimum 32 bytes), and `IDENTITY_NOTIFICATION_WORKLOAD_TOKEN`. An enabled email/push adapter additionally requires an HTTPS (or loopback-only development) provider origin and provider token.
- For local provider-independent exercises, set `NOTIFICATION_LOCAL_DEVELOPMENT_PROVIDERS=true` with both external adapters disabled. Email and push deliveries then complete into the durable delivery state with deterministic `local-dev-<delivery-id>` provider IDs and never contact a network. This mode is explicitly unsuitable for production and does not simulate vendor acceptance or deliver to a real destination.
- If realtime fanout is enabled, set a unique `NOTIFICATION_INSTANCE_ID` for every service replica (or use its unique pod `HOSTNAME`). The service derives a private `notification-realtime-<instance-id>` Kafka group and fails startup for a missing/unsafe identity; this broadcasts delivery status to each process-local bounded SSE hub instead of load-balancing it away.

Provider-vendor-specific rendering integration, automated outbox pruning, Kafka ACL/topic provisioning, gateway/Compose route deployment, and a production provider/SSE end-to-end deployment remain follow-up work.
