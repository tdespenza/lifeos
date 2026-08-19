# Distributed scheduler

Design exercise only — this is a proposed architecture, not a production deployment.

## Requirements

- Authorized callers register one-time or recurring jobs. Workers execute due jobs at least once;
  application handlers must use the supplied execution key to make business effects idempotent.
- Assume 1 million active schedules, 20,000 due executions per minute, 1-second schedule granularity,
  a 30-second normal dispatch delay target, and an execution payload cap of 32 KiB. These are design
  inputs, not service-level commitments.
- A schedule update is versioned; pause/cancel wins over an unclaimed execution but cannot revoke a
  handler already executing. Misfire policy is explicit: `SKIP`, `RUN_ONCE`, or bounded `CATCH_UP`.
- Arbitrary user code, unbounded recurring catch-up, and exactly-once handler side effects are out of
  scope.

## API shape

| Operation | Shape | Contract |
| --- | --- | --- |
| Register | `POST /v1/jobs` | Caller auth and `Idempotency-Key`; body has handler name, schedule, time zone, payload, deadline, and misfire policy; returns `201 jobId`, version. |
| Change state | `PATCH /v1/jobs/{id}` | Owner/workload authorization and `If-Match`; updates pause, cancellation, or future schedule only. |
| Inspect | `GET /v1/jobs/{id}` | Returns schedule state and bounded recent execution summary, not worker internals. |
| Claim/complete | `POST /internal/executions/{id}/claim` and `/complete` | mTLS worker identity; fenced lease token, deadline, and idempotent completion result are required. |

An execution has a deterministic ID derived from `(jobId, scheduledFor, scheduleVersion)`. The handler
receives this ID as its idempotency key and may reject a stale fencing token.

## Data model

`job(id, owner_id, handler, schedule_expression, timezone, state, version, next_run_at,
misfire_policy, payload_ciphertext)` is authoritative. `execution(id PK, job_id, scheduled_for,
schedule_version, state, lease_owner, lease_until, fencing_token, attempt_count, outcome)` has a
unique `(job_id, scheduled_for, schedule_version)` constraint. `schedule_bucket(bucket_start,
shard, execution_id)` is a materialized due-work index. Audit records capture mutations and handler
outcomes without payload values.

## Scaling and partitioning

Bucket due work by minute (or second for a premium tier) and hash `job_id` into a fixed shard count.
Scheduler leaders claim only their current bucket/shard leases; workers pull from a partitioned ready
queue and are bulkheaded by handler class. The job record computes the next execution transactionally
when a current execution is materialized, avoiding an unbounded global cron scan.

Clock ownership uses database time or a monotonic trusted time service for lease comparisons. Adding
shards creates a new partition map at a future bucket boundary, copies outstanding buckets, and uses
fencing tokens so old owners cannot complete after handoff.

## Bottlenecks and tradeoffs

The near-now bucket and slow handlers are likely bottlenecks. Fine-grained buckets lower jitter but
raise coordination work; minute buckets scale more easily but cannot promise sub-second dispatch.
Pull workers with leases handle uneven job duration better than a global push coordinator, at the
cost of at-least-once executions during lease expiry. A single leader would simplify ordering but is
a throughput and availability bottleneck, so this design orders only within job/schedule versions.

## Failure and recovery

The scheduler inserts an execution before enqueueing it through a transactional outbox. A claimed
execution that loses its worker/lease becomes eligible after `lease_until`; completion compares the
fencing token and is idempotent. Transient handler failures use bounded exponential backoff capped by
the job deadline. Permanent failures or exhausted retries move to a durable dead-letter state for
owner inspection; they are not silently skipped.

After a scheduler crash, new leaders scan only bounded recent buckets and unexpired/expired leases,
then apply each job's misfire policy. A reconciliation job finds an execution row missing a queue
message and republishes it by execution ID. Workers stop claiming during database/time-service
unavailability rather than running from an untrusted local clock.

## Observability

Measure due-to-claim delay, claim-to-complete duration, bucket scan duration, ready queue depth,
lease expiration/reclaim count, per-handler saturation, retries, dead letters, misfire decisions,
and schedule-version conflicts. Trace registration through materialization and execution with job and
execution IDs; logs store HMACed IDs and error classes. Alert on schedule lag beyond the target,
orphaned outbox rows, clock drift, rising lease loss, handler bulkhead saturation, or dead-letter
growth.

## Security and privacy

Authorize callers per job/tenant and authenticate workers with workload identities restricted to
their handler classes. Validate schedule expression complexity, time zones, payload schema/size, and
destination/handler allow-lists. Encrypt job payloads, rotate keys, and never log them. Rate-limit
registration to prevent bucket abuse, retain audit history separately from payload retention, and
return generic missing responses for jobs outside the caller scope.
