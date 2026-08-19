# Notification system

Design exercise only — this is a proposed architecture, not a production deployment.

## Requirements

- An authorized product service requests a notification for one recipient or a bounded audience;
  recipients may receive email, push, in-app, or an SSE hint according to encrypted endpoint and
  preference records. A sender never reads another recipient's endpoints.
- Assume 5,000 accepted requests per second at peak, up to three channels per notification, 30-day
  delivery audit retention, and a user-visible in-app record within 10 seconds for accepted work.
  Delivery is at-least-once internally and externally deduplicated when a provider supports a key.
- The acceptance API is idempotent for 24 hours. Opt-out, suppression, quiet-hours, and per-channel
  rate limits are checked before delivery work is scheduled. Provider delivery is never synchronous
  with the caller's acceptance request.
- Campaign authoring, arbitrary HTML, and an actual provider account are outside this exercise.

## API shape

| Operation | Shape | Contract |
| --- | --- | --- |
| Accept | `POST /v1/notifications` | Service/workload auth, `Idempotency-Key`; body includes recipient reference, template ID, locale, channel candidates, and bounded data fields; returns `202 notificationId`. |
| Inspect | `GET /v1/notifications/{id}` | Sender with a scoped grant or recipient; returns privacy-safe aggregate state and attempt summary. |
| Preferences | `PUT /v1/recipients/me/notification-preferences` | Recipient auth, versioned with `If-Match`; no endpoint secret is returned. |
| Provider callback | `POST /internal/providers/{provider}/receipts` | mTLS/signed callback with replay protection; updates an attempt idempotently. |

`notificationId` is server generated. The service canonicalizes the accepted payload and persists the
idempotency snapshot before replying; changing payload under the same key is `409`.

## Data model

`notification(id, tenant_id, recipient_id, template_version, payload_ciphertext, state,
created_at, expires_at)` is immutable content intent. `delivery_attempt(id, notification_id,
channel, provider, state, next_attempt_at, lease_until, provider_key, attempt_count)` represents
one bounded execution path. `recipient_endpoint` and `preference` are separately encrypted and
versioned. A transactional `notification_outbox` records accepted work; a receipt ledger has a
unique `(provider, provider_message_id, receipt_type)` key. Payloads contain template variables,
not rendered secrets, and expire with the notification.

## Scaling and partitioning

Partition the delivery stream by `recipient_id` to preserve per-recipient preference ordering and
avoid concurrent duplicate channel sends. A scheduler claims due attempts with a leased, indexed
`next_attempt_at` range; separate worker pools/bulkheads isolate email, push, and in-app providers.
Large audience requests are expanded from an immutable audience snapshot into fixed-size recipient
pages, with a maximum audience size and a deliberate queue admission decision.

Endpoint/preference reads use a recipient-keyed cache with invalidation on version change. The sender
API stores only intent and outbox rows in its transaction; relays and workers scale independently.

## Bottlenecks and tradeoffs

Provider quotas and the due-attempt index are likely first bottlenecks. Per-provider token buckets
and bounded queues protect providers, while backpressure visibly delays non-critical work. A global
priority queue would improve cross-recipient ordering but creates a hot coordinator, so this design
chooses recipient ordering and priority bands within partitions. Exactly-once provider delivery is
not promised: idempotency keys minimize duplicates, and recipients must tolerate rare provider
duplicates.

Rendering at delivery time allows current localization but risks template drift; pinning a template
version makes retries deterministic at the cost of storing more historical templates. This exercise
pins the version.

## Failure and recovery

An accepted notification is durable before `202`; an outbox relay retries publication with exponential
jitter. Workers lease an attempt, use explicit provider connect/read timeouts, and retry only
classified transient failures. An expired lease returns work to the queue. Permanent failures,
attempt-budget exhaustion, or invalid endpoints transition to a dead-letter/suppression state and
create a privacy-safe audit event. Provider callbacks are idempotent and may arrive after a timeout.

Recovery reconciles due attempts, expired leases, and outbox rows by primary key. A provider outage
opens that provider's circuit and stops consuming its bounded queue; non-dependent channels continue
when policy permits. No worker marks an unconfirmed provider send as successful.

## Observability

Measure acceptance latency, outbox age, queue depth, provider circuit state, schedule/lease lag,
attempt/retry/dead-letter counts by channel and error class, end-to-end delivery latency, suppression
rate, and callback verification failures. Trace one notification ID through acceptance, relay, and
attempts; use a hashed recipient ID only in logs. Audit records capture sender workload, recipient
scope, template version, policy decision, and outcome. Alert on growing due-work lag, a dead-letter
spike, provider quota saturation, or an outbox relay that stops advancing.

## Security and privacy

Use workload authentication for senders, recipient/tenant authorization for reads and preferences,
and mTLS or signed timestamps for provider callbacks. Validate template schema and field count/size;
do not accept raw provider payloads or arbitrary HTML. Encrypt endpoint addresses and payload fields,
rotate keys, and keep rendered messages and tokens out of logs, traces, and metric labels. Enforce
unsubscribe/consent, quiet-hours, retention/deletion workflows, and generic errors that do not reveal
whether a recipient has an endpoint.
