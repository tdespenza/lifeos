# Event analytics pipeline

Design exercise only — this is a proposed architecture, not a production deployment.

## Requirements

- Authorized product services publish immutable, versioned business events; analytics creates
  tenant-scoped near-real-time aggregates and bounded queries. Product writes remain authoritative;
  analytics is a rebuildable projection and must not decide access, billing, or operational commands.
- Assume 100,000 events per second, 30-day raw-event retention, 13-month daily aggregates, 5-minute
  freshness target for standard dashboards, 50 query rows per page, and at-least-once transport with
  idempotent aggregation. These are exercise assumptions, not deployed throughput claims.
- Every event has an event ID, producer, schema version, occurred-at, tenant scope, and privacy
  classification. Unknown schemas, invalid events, and exhausted processing attempts enter a
  quarantine path rather than disappearing.
- Cross-tenant reporting, raw PII analytics, irreversible enrichment, and real-time ML training are
  out of scope.

## API shape

| Operation | Shape | Contract |
| --- | --- | --- |
| Publish | `POST /internal/events` or producer outbox relay | Workload authentication; CloudEvents-like envelope with schema ID/version, event ID, tenant, partition key, and bounded payload; duplicate event ID is acceptable. |
| Query aggregate | `GET /v1/analytics/{metric}?from=&to=&cursor=` | Tenant/user authorization; validated metric/filter allow-list and bounded date range; returns data freshness/checkpoint. |
| Manage definition | `PUT /v1/admin/analytics-metrics/{id}` | Privileged, versioned definition and retention policy; creates a backfill job instead of scanning inline. |
| Repair | `POST /internal/analytics-replays` | Privileged partition/time-range request with a work budget and audit trail. |
| Quarantine review | `GET /internal/analytics-quarantine` | Restricted operator role; redacted envelope/error metadata, not raw sensitive payload. |

Producers use transactional outbox records so an accepted domain mutation and event are not a dual
write. Query responses identify aggregate definition/version and watermark rather than claiming an
unqualified real-time view.

## Data model

`event_envelope(event_id, producer, schema_version, tenant_id, occurred_at, partition_key,
privacy_class, payload)` is retained only in the immutable log/object archive under policy.
`consumer_checkpoint(consumer, partition, offset, watermark)` tracks replay. `dedup(event_id,
consumer, expires_at)` makes aggregation idempotent within raw retention. `metric_definition(id,
version, dimensions_allowlist, aggregation, retention)` governs computation. `metric_bucket(tenant_id,
metric_id, definition_version, bucket_start, dimension_hash, value, event_count, updated_at)` is the
query projection. `quarantine(event_id, stage, error_class, observed_at)` stores repair metadata
without broad payload exposure.

## Scaling and partitioning

Partition the event log by tenant plus a stable entity hash to preserve per-entity order while
spreading large tenants. Stream processors own partitions and maintain local keyed state/checkpoints;
they write aggregates to a database partitioned by tenant, metric, and time bucket. A query router
selects only the caller's tenant buckets, limits requested time range/dimensions, and aggregates a
bounded number of rows. Cold raw events and old aggregate buckets move to encrypted object storage
with a catalog, while current windows remain in low-latency stores.

Backfills use a separate consumer group and capped partition range so they cannot starve live
freshness. A new metric definition writes to a versioned projection; only after validation does the
query alias switch to it. This permits rollback without mutating historical aggregate rows in place.

## Bottlenecks and tradeoffs

Skewed tenant/entity keys, state-store growth, and high-cardinality dimensions are the main risks.
The schema/definition registry caps dimension cardinality, payload size, and date range; excessive
data becomes an explicit rejection or a coarser bucket, not an unbounded memory table. Exactly-once
streaming is costly across arbitrary sinks, so durable event IDs plus idempotent aggregate writes are
chosen. This can temporarily reprocess work but preserves correctness without silently dropping it.

Pre-aggregated daily/minute buckets make dashboards fast but lose arbitrary drill-down detail. Raw
events allow replay but increase privacy/retention exposure; this design keeps them only as long as
the approved policy and then relies on anonymized aggregates where permitted.

## Failure and recovery

Consumers commit checkpoints only after durable dedup and aggregate writes. Transient failures retry
with jitter and bounded attempts; poison data or unknown schemas move to quarantine with producer,
schema, partition, and error class. A lagging partition publishes its watermark and dashboard queries
show freshness age rather than silently mixing old/new totals. Processor state restores from a
checkpoint plus replayed log; aggregate reconciliation compares event counts/checkpoints for bounded
windows and rebuilds a versioned metric projection when necessary.

Producer outbox relay failure retains events transactionally until published. During analytics-store
outage, consumers pause/backpressure rather than acknowledge and lose events. Retention deletion is
an auditable lifecycle job that verifies both raw archives and derived state before completion.

## Observability

Measure producer outbox age, publish rate/error, per-partition consumer lag, watermark age, state
size, checkpoint commit latency, dedup hit/miss, aggregate write latency, quarantine count/age,
backfill progress, query p95, result row count, and retention/purge completion. Trace by correlation
and event ID hash across producer/relay/consumer, avoiding payload/dimension values. Alert on rising
lag, stuck checkpoints, unexpected dedup spikes, high-cardinality rejection, quarantine growth,
aggregate reconciliation mismatch, or a stale dashboard watermark.

## Security and privacy

Authenticate producers by workload and authorize query callers to a trusted tenant scope; do not
trust a caller-supplied tenant ID. Validate event schema/size/version before publication, sign or use
mTLS for transport, encrypt logs/state/backups, and restrict raw-event access to a small operator
role. Classify/minimize payload fields, HMAC identifiers where aggregation does not need them, ban
PII from metric labels/logs, enforce per-class retention/deletion, and return generic inaccessible
responses so analytics queries cannot enumerate another tenant's activity.
