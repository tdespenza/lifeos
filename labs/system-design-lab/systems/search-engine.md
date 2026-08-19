# Search engine

Design exercise only — this is a proposed architecture, not a production deployment.

## Requirements

- Authorized users search only documents they can currently access; source services remain the
  authority for document existence and authorization. Search is a derived projection and may be
  stale for up to 60 seconds after a source mutation.
- Assume 2,000 queries per second, 200 source mutations per second, 100 million indexed documents,
  query length 2–128 normalized characters, a maximum page size of 50, and p95 query latency under
  250 ms. These are exercise assumptions, not measurements.
- Create/update/delete events are idempotently indexed by source document version. A deletion must
  become a tombstone before a result is returned; the system may confirm access with the source on a
  cache miss or high-risk query.
- Semantic retrieval, crawler ingestion, and unrestricted cross-tenant search are out of scope.

## API shape

| Operation | Shape | Contract |
| --- | --- | --- |
| Search | `GET /v1/search?q=&cursor=&limit=&filters=` | Caller auth; validates query grammar and filter allow-list; returns a keyset cursor, bounded highlights, result version, and projection freshness marker. |
| Suggest | `GET /v1/search/suggestions?q=` | Maximum ten normalized terms; a privacy-filtered, rate-limited convenience endpoint. |
| Index source | `POST /internal/index-events` | Authenticated event relay; schema includes source ID, tenant, version, operation, and opaque correlation ID; duplicate `(sourceId, version)` is a no-op. |
| Reindex | `POST /internal/reindex-jobs` | Privileged, partition-scoped and rate-limited; returns a job ID rather than scanning all data inline. |

Search never accepts raw query-language fragments. Result identifiers are re-authorized or minted as
short-lived opaque references before a caller can fetch source content.

## Data model

`source_version(source_id, tenant_id, version, deleted, committed_at)` is the indexing fence.
`search_document(tenant_id, source_id, version, title_terms, body_terms, filters, updated_at)` is a
versioned index record; it stores only fields approved for discovery, not original files. A separate
inverted index maps `(tenant_id, normalized_term)` to bounded posting blocks. `index_checkpoint`
records source-stream offsets and schema version. Query logs hold a salted query fingerprint and
aggregate counters, not raw content by default.

## Scaling and partitioning

Tenant ID is the authorization partition and the first index routing key; large tenants receive a
secondary hash of source ID or term block. Query coordinators fan out only to known tenant shards,
merge a bounded top-K from each, then apply deterministic score/updated-at/ID ties. Posting lists are
segmented and compressed; write workers batch events by shard while preserving version fences per
source ID. Replicas serve queries, while primary index writers own mutation order.

Reindexing uses a new index generation per partition, replays from a recorded checkpoint, validates
document/version counts, and atomically swaps the read alias. The previous generation is retained
only for a bounded rollback window.

## Bottlenecks and tradeoffs

High-frequency terms and fanout across very large tenants are the likely latency limit. Query parsing
caps boolean clauses, prefix expansions, wildcard length, and result windows; callers receive a
validation error rather than an expensive unbounded query. Strongly authorizing every result against
the source reduces stale-access risk but adds latency and load; this design uses a short-lived access
version cache plus source confirmation for revocation-sensitive resources.

Indexing all source text improves recall but broadens the privacy boundary, so only an explicit
discovery projection is indexed. Eventual indexing gives availability and manageable write throughput
but reports a freshness marker instead of presenting results as immediately consistent.

## Failure and recovery

Event consumers commit a checkpoint only after a versioned index write succeeds. Transient shard
errors retry with jitter and bounded attempts; poison events move to quarantine with the schema/error
class, then are repaired and replayed by offset. A lagging partition serves its last valid generation
with a visible freshness age, while a corrupted partition is removed from query routing rather than
returning partial results silently. Source events can rebuild a partition from a snapshot plus offset.

Query coordinators time out individual shards, lower the result completeness marker, and fail closed
for a tenant when its authorization/freshness fence is unavailable. They do not mix results from an
unknown tenant shard.

## Observability

Track query p50/p95/p99, parse rejections, fanout count, posting-block reads, candidate count before
ranking, cache hit rate, per-partition index lag, checkpoint age, reindex duration, quarantine count,
and source-version conflicts. Traces include a correlation ID, tenant hash, shard count, and result
count, never raw queries or documents. Alert on lag beyond freshness budget, a failed generation
swap, query-timeout/partial-result rate, or a hot-term partition exceeding its CPU budget.

## Security and privacy

Authenticate every API call and derive tenant scope from trusted claims, never from a client filter.
Normalize Unicode, bound CPU/memory for parsing, and rate-limit suggestion/query endpoints to resist
enumeration and expensive-query abuse. Encrypt index volumes and backups, restrict operators from
reading raw projection fields, and enforce deletion through source tombstones plus verified index
purge. Use generic empty/not-found behavior for inaccessible content and exclude raw query text,
document titles, and tenant IDs from broad logs and metric labels.
