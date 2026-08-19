# Recommendation engine

Design exercise only — this is a proposed architecture, not a production deployment.

## Requirements

- An authorized user requests a small ranked list of currently eligible items; a client may record
  impression, dismissal, or conversion feedback. Recommendations are assistive, never an authority
  for access control, billing, safety, or high-impact decisions.
- Assume 3,000 reads per second, 500 feedback events per second, 20 million items, a maximum 100
  candidates per request, 20 returned results, and p95 response time below 150 ms from a warmed
  candidate cache. Values are design assumptions, not deployment measurements.
- Each response identifies its model and feature snapshot version. Feedback writes are idempotent;
  users can opt out, delete their history, and receive a non-personalized fallback.
- Real-time model training, opaque sensitive inference, and experimentation policy are out of scope.

## API shape

| Operation | Shape | Contract |
| --- | --- | --- |
| Recommend | `GET /v1/recommendations?context=&limit=` | User auth; bounded context allow-list; returns eligible item references, reason codes, model version, and cache/freshness marker. |
| Feedback | `POST /v1/recommendations/{requestId}/feedback` | User auth and `Idempotency-Key`; body is one bounded event such as impression/dismissal/click. |
| Opt out | `PUT /v1/users/me/recommendation-preferences` | Versioned preference write; disabling personalization selects a generic popularity/recency fallback. |
| Train/publish | `POST /internal/model-releases` | Privileged workflow; requires data contract/version checks and a rollback-ready release ID. |

Item references are re-authorized by the source service when opened. The recommendation service never
returns a source object merely because a model once observed it.

## Data model

`candidate(item_id, segment_id, eligibility_version, features_version, score_inputs, expires_at)`
is a derived, expiry-bounded projection. `user_profile(user_id, consent_version, feature_version,
encrypted_features, deleted_at)` is mutable and minimizes raw events. `recommendation_request(id,
user_id, model_version, candidate_snapshot, created_at)` supports feedback attribution. `feedback`
has a unique `(user_id, idempotency_key_hash)` and links to a request/model version. `model_release`
records training data lineage, evaluation gates, and rollback state—not raw training data.

## Scaling and partitioning

Partition feature and candidate projections by stable user/segment hash. Precompute broad candidate
sets by segment, then perform a bounded per-user rerank from at most 100 candidates. A local cache
holds a short-lived response keyed by user, consent version, context, and model version; it is
invalidated on opt-out, access revocation, or model rollback. Feedback is partitioned by user for
order, then streamed into feature aggregation and offline training stores.

Hot users have a per-user request coalescer and rate limit. New model releases use shadow evaluation
and a versioned cache namespace before a gradual routing shift; no partition changes model version
without the release fence.

## Bottlenecks and tradeoffs

Feature freshness and candidate fanout dominate latency. Precomputation improves response time but
can be stale, so returned results carry a freshness marker and expiry. A fully online model could
react faster but adds dependency variability and a larger privacy surface. This design prefers
bounded reranking of an offline candidate set, accepting that it may miss a newly relevant item.

Personalization can amplify feedback loops. Diversity caps, source quotas, explicit dismissals, and
an auditably deterministic tie-breaker reduce repeated exposure at the cost of a lower raw score.

## Failure and recovery

If features, candidate cache, or model scorer fail, return a bounded, consent-safe generic fallback
or a retryable error—never an unfiltered item list. Feedback is written to a durable outbox before
acknowledgment and replayed by event ID; invalid events go to quarantine with schema/error metadata.
Model release failures leave the prior release active. A faulty release is removed by a routing flag,
cache namespace invalidation, and replayable score generation from retained feature snapshots.

Deletion/opt-out emits a high-priority purge record. Consumers fence future personalization by consent
version, delete derived features/caches, and record completion; until confirmed, requests use the
non-personalized fallback.

## Observability

Track request latency, cache hit rate, candidate count, scorer timeout/fallback rate, model/version
distribution, feature freshness, feedback ingestion lag, opt-out/purge lag, diversity/eligibility
filter counts, and release rollback count. Trace an opaque request ID through selection and feedback;
log model/feature versions and reason categories, not raw features or item titles. Alert on fallback
spikes, stale features, a release quality-gate failure, or a purge backlog exceeding policy.

## Security and privacy

Require authenticated user context and derive tenant/consent from trusted identity facts. Enforce item
eligibility before ranking and again at click-through. Encrypt feature stores, segregate training
access, minimize retention, and ban protected/sensitive attributes unless an approved policy and
impact review exist. Bound feedback fields, throttle abusive clients, avoid exposing why another user
received an item, and give deletion/opt-out generic, auditable completion semantics.
