# URL shortener

Design exercise only — this is a proposed architecture, not a production deployment.

## Requirements

- An authenticated owner creates, expires, disables, and lists short links; an unauthenticated
  reader resolves a live code to a redirect. A missing, expired, disabled, or inaccessible owner
  link has the same public not-found response.
- Assume 100 create/update requests per second, 10,000 redirect requests per second, 50 million
  active links, a 2 KiB destination URL cap, and a redirect p99 target below 80 ms in one region.
  These are exercise inputs, not observed production numbers.
- Link creation accepts an `Idempotency-Key` for 24 hours. A custom alias is globally unique and
  immutable after creation; generated codes are random, URL-safe 64-bit values with collision retry.
- Click analytics are best-effort and must never delay a redirect. Link ownership, disablement, and
  expiry are source-of-truth state; cache entries are derived state.

## API shape

| Operation | Shape | Contract |
| --- | --- | --- |
| Create | `POST /v1/links` | Bearer auth and `Idempotency-Key`; body has `destinationUrl`, optional `alias`, and optional `expiresAt`; returns `201` with code and version. |
| Read/manage | `GET/PATCH /v1/links/{code}` | Owner-only; `PATCH` uses `If-Match: "version"` to change expiry or disabled state. |
| Resolve | `GET /r/{code}` | Returns `302 Location` for a live link, cacheable only up to its expiry; returns generic `404` otherwise. |
| List | `GET /v1/links?cursor=&limit=` | Owner-filtered, keyset-paginated, maximum 100 records. |

The create response stores an HMAC of the idempotency key and a canonical request fingerprint. A
reused key with changed content returns `409`; a replay returns the original status and body.

## Data model

`link(code PK, owner_id, destination_ciphertext, destination_key_id, status, expires_at,
version, created_at, updated_at)` is the durable authority. `code` has a unique constraint and
`owner_id, created_at, code` supports owner listing. `link_command(owner_id, key_hash, fingerprint,
response_snapshot, expires_at)` provides bounded idempotency. A redirect cache contains only `code`,
destination, status, and an expiry no later than the link expiry. A `LinkResolved` outbox event has
an opaque event ID and never blocks the redirect path.

## Scaling and partitioning

Partition durable links by a stable hash of `code`; a custom-alias reservation routes to the same
partition before creation, so uniqueness is checked at one authority. A near-cache plus distributed
read-through cache absorbs hot redirects, with a negative-cache TTL under 10 seconds to avoid making
new links invisible for long. Cache invalidation is published from the committed link transaction.

For a multi-region design, creation is home-region routed by code hash and redirects read a local
replica/cache. Cross-region disablement is allowed to be briefly stale only if redirect responses use
a small cache TTL; a safety-critical immediate disable instead bypasses cache through a global
revocation set. Rebalancing copies a shard, tails its change log, fences writes, and then switches
the partition map.

## Bottlenecks and tradeoffs

The redirect cache and hot vanity codes are the first likely bottlenecks. Per-code request coalescing
and a short-lived local cache prevent a thundering herd; a per-code limiter protects one viral link
without penalizing unrelated codes. Random codes avoid a central sequence allocator but are not
human-meaningful. A globally serializable alias directory simplifies uniqueness but adds create
latency; allowing aliases to be only tenant-unique would reduce coordination but changes public URLs.

Click events intentionally trade completeness for redirect latency. The redirect returns after the
authoritative read/cache lookup and enqueues a bounded event; when the buffer is full, it increments
a drop metric instead of retaining unbounded click data in memory.

## Failure and recovery

Every cache and analytics call has a short timeout and is optional. On cache failure, resolve from
the shard with a bounded bulkhead; on shard failure, return a generic retryable `503` rather than
redirecting from stale data beyond revocation policy. Writes use the idempotency record and database
transaction, so a client retry can recover an uncertain create. The outbox relay retries transient
analytics publication with jitter and sends exhausted records to a quarantine topic/table.

Cache data is disposable: rebuild it by warming from redirect traffic or scanning durable live links
in bounded partitions. A reconciliation job compares committed invalidation versions with cache
versions and evicts stale keys; it never reconstructs a destination from analytics events.

## Observability

Track redirect p50/p95/p99, cache hit/negative-hit ratio, shard lookup latency, create conflicts,
idempotency replays, alias collisions, expired/disabled resolve counts, invalidation lag, and dropped
click-event count. Trace create and management writes through database/outbox work; sample redirect
traces at a bounded rate. Structured audit events record owner, action, result, code hash, and
correlation ID—not the destination URL. Alert on shard error rate, cache eviction storms, invalidation
lag, or a hot-code limiter saturating.

## Security and privacy

Validate scheme (`https` by default), DNS/IP policy, length, Unicode normalization, and redirect
targets to block header injection, `javascript:` URLs, and SSRF-like internal targets. A redirect
endpoint has IP/device rate limits and abuse detection; management actions require owner-scoped
authorization and CSRF protection for browser sessions. Encrypt destination URLs at rest, HMAC code
values in audit data, redact query strings from logs, and expire click aggregates according to a
published retention policy. Do not expose whether a code ever existed to an unauthenticated caller.
