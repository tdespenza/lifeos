# Rate limiter

Design exercise only — this is a proposed architecture, not a production deployment.

## Requirements

- Enforce independently configurable limits for an authenticated tenant, user, API route, workload,
  IP-prefix, and expensive-operation class. The most restrictive applicable rule wins, while a
  request can be charged to more than one bucket.
- Assume 100,000 checks per second, a 1 ms p99 local decision target, fixed 1–60 second windows or
  token-bucket refill periods, and a maximum of eight evaluated rules per request. These are design
  assumptions, not measured capacity.
- Decisions must be explainable with a stable policy version, retry-after value, and non-sensitive
  rule identifier. A client never receives another principal's remaining quota.
- It is out of scope to guarantee globally exact counts during a loss of all coordination stores.
  The safety behavior for each route class must be explicit instead.

## API shape

| Operation | Shape | Contract |
| --- | --- | --- |
| Check and consume | `POST /internal/limits/check` | Gateway/workload identity; body has trusted principal facts, route class, cost 1–100, and request ID; response is `allow`, `remaining`, `retryAfterMs`, and policy version. |
| Configure | `PUT /v1/admin/rate-limit-policies/{id}` | Privileged, versioned `If-Match` update for bounded scopes and limits. |
| Inspect policy | `GET /v1/admin/rate-limit-policies` | Privileged, cursor-paginated; counter state is never exposed. |
| Explain denial | `GET /internal/limits/decisions/{requestId}` | Short-lived, workload-restricted audit lookup with hashed subject values. |

The gateway maps an allowed decision to standard `RateLimit-*` headers and a denied decision to
`429` plus `Retry-After`. Request IDs make retry/diagnostic records idempotent but do not turn an
arbitrary retried business write into one.

## Data model

`policy(id, scope_type, scope_selector, route_class, algorithm, capacity, refill_rate,
version, enabled)` is source-of-truth configuration. A compiled, signed policy snapshot is distributed
to decision nodes. `counter(key_hash, window_or_bucket, tokens_or_count, updated_at, policy_version)`
lives in a coordination store with TTL no longer than the bucket. `decision_audit(request_id,
policy_version, outcome, rule_id, subject_hash, expires_at)` is a short-retention troubleshooting
record. No raw IP, bearer token, or user ID appears in counter keys or audit data.

## Scaling and partitioning

Hash a canonical `(policyVersion, rule, subject)` key to a coordination-store shard. A single script
or compare-and-set operation consumes all required dimensions atomically when they share a shard;
otherwise the decision node reserves in a deterministic order and refunds on a later denial. Large
tenant-wide buckets use a separate hash key from per-user buckets to avoid one hot tenant starving
all lookups. Local immutable policy caches refresh by version and remain bounded by policy count.

For very high-rate low-risk endpoints, a node receives a leased sub-bucket from the global counter;
it can decide locally until the lease is spent or expires. That lowers coordination load but permits
bounded overshoot equal to outstanding leases, which is documented per policy.

## Bottlenecks and tradeoffs

The counter store and hot tenant keys are the primary bottlenecks. Token buckets smooth bursts but
allow a bounded initial burst; fixed windows are cheaper but have boundary spikes. A global exact
counter offers fairness but adds every-request latency and a single dependency, so this design uses
atomic shard-local counters plus optional leased sub-buckets for low-risk traffic. Per-route policy
evaluation caps rule count and rejects overly broad selector configurations before rollout.

IP limits are useful against anonymous abuse but are unfair behind NATs; they complement, never
replace, authenticated subject limits. Cost-based charging protects expensive operations but requires
the gateway to derive cost from trusted route metadata rather than client input.

## Failure and recovery

Decision calls have tight timeouts, a bulkhead, and no automatic retry on a live request because a
late retry might double-consume a bucket. On coordination-store loss, payment, authentication, and
expensive writes fail closed; idempotent, low-risk public reads may use a deliberately small local
emergency bucket. The chosen fallback and maximum duration are versioned in policy. Nodes emit a
policy-staleness alarm and stop using an expired policy snapshot.

Counter state is intentionally ephemeral. After a shard failover, its TTL-bound counters restart
conservatively or restore from the replicated store; configuration restores from the authoritative
database. A reconciliation process validates policy distribution version and removes stale local
leases. It does not attempt to reconstruct precise historical request counts from logs.

## Observability

Measure check latency, allow/deny/error/fallback count by route class and policy ID, remaining-token
distribution, coordination-store script latency, hot-key concentration, local-lease use/overshoot,
policy propagation age, and fail-open/closed activations. Trace decision calls with correlation ID
and rule IDs; logs contain only HMACed subject keys. Alert on store errors, policy staleness, a sharp
deny increase, hot-shard saturation, or emergency fallback lasting beyond its configured window.

## Security and privacy

Only a trusted gateway or workload may call the decision API; client-supplied headers cannot select
a subject, route class, or cost. Sign/verify distributed policy snapshots, restrict policy mutation
by tenant/admin scope, validate selector grammar, and rate-limit the policy plane separately. Use
HMACed counter/audit keys with key rotation, keep raw IP/token data out of telemetry, apply short
decision-audit retention, and return generic `429` behavior that does not reveal another account's
quota or existence.
