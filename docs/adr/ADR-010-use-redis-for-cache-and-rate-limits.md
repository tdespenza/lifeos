# ADR-010: Use Redis for caching, sessions, and rate limiting

## Context

LifeOS runs as a set of independently deployable Spring Boot microservices, each scaled horizontally behind a load balancer. Several cross-cutting needs span these instances: the API Gateway must enforce rate limits consistently regardless of which instance handles a request; the Identity Service must validate and revoke sessions/tokens the instant a user logs out or is compromised, from any instance; and the Finance Service needs a fast read-through cache in front of PostgreSQL for account summaries and computed aggregates that are expensive to recompute on every request. All three needs share a common shape: high-churn, latency-sensitive state that must be visible to every service instance immediately, and that does not need to survive permanently if lost.

## Options Considered

- **Caffeine (in-process cache only)** — extremely fast (no network hop) but scoped to a single JVM. The moment a service runs more than one instance, each instance has a divergent view of rate-limit counters and session state, which is disqualifying for the Gateway and Identity Service. Would require a broadcast/invalidation layer to even approximate correctness, effectively reinventing a distributed cache.
- **Memcached** — a proven distributed cache with simple key/value semantics and predictable latency. Rejected because it lacks the data structures we need directly: no atomic sorted sets or `INCR`-with-TTL for sliding-window rate limiting, no pub/sub for lightweight cross-service notifications, and no native distributed-lock primitive (`SET NX PX`). We would need to layer custom logic and an additional coordination service on top, adding complexity Redis provides natively.
- **PostgreSQL-backed session/rate-limit tables** — reuses infrastructure we already operate and gets durability for free. Rejected because sessions and rate-limit counters are extremely high-churn (every request potentially writes) and don't need durability — losing a rate-limit counter on restart is a non-event, but routing that write volume through the primary OLTP datastore adds unnecessary load, connection pressure, and lock contention to a system that also serves financial transactional data. It also couples availability of core financial writes to the availability of an unrelated cross-cutting concern.

## Decision Made

Use Redis as the shared, distributed layer for caching, session storage, token revocation lists, rate limiting, distributed locks, and lightweight pub/sub notifications across all services, deployed as a managed/clustered Redis instance separate from PostgreSQL and MongoDB.

## Why

Redis is the only option among those considered that satisfies all three requirements natively without bolted-on infrastructure: atomic `INCR`/`EXPIRE` and sorted sets give correct, race-free rate limiting across instances; `SETEX`/`SET NX` give both TTL-based session expiry and revocation plus simple distributed locks; and pub/sub covers cross-instance cache invalidation and lightweight fan-out notifications. Sub-millisecond in-memory access keeps it viable on the hot path (every gateway request, every authenticated call), and it keeps this volatile, high-churn traffic off PostgreSQL, preserving headroom on the datastore that holds durable financial and identity records.

## Tradeoffs

- We introduce a new stateful dependency that must be operated, monitored, and kept highly available; a Redis outage degrades rate limiting, session validation, and Finance Service read paths simultaneously, since they share the same cluster.
- Redis is primarily in-memory: data is not durable by default (AOF/RDB persistence adds latency and operational complexity we're deliberately not fully enabling for cache/session use cases), so a hard restart can drop active sessions and force re-authentication, and can momentarily reopen a rate-limit window.
- Clustering Redis for HA adds operational surface (Sentinel or Cluster mode, failover behavior, client-side topology awareness) beyond a single-node deployment.
- Using Redis as a cache in front of PostgreSQL in the Finance Service introduces cache-invalidation correctness risk — stale financial reads are a real hazard that requires explicit TTL and write-through/invalidation discipline, not a "cache everything" default.

## Consequences

- The API Gateway and Identity Service take a hard runtime dependency on Redis; both must define explicit fail-open vs. fail-closed behavior for a Redis outage (we fail closed on rate limiting to protect backends, and fail closed on session validation to protect security, accepting a full outage of the Gateway/Identity path if Redis is down).
- All services touching Redis need consistent key-naming, TTL, and eviction-policy conventions to avoid cross-service key collisions and unbounded memory growth (`maxmemory` + `allkeys-lru`/`volatile-lru` policy required from day one).
- Finance Service cache entries must never be treated as source of truth; PostgreSQL remains authoritative, and cache reads must tolerate staleness within a documented bound.
- Operationally, this adds Redis to the observability stack (latency, memory, eviction rate, connected clients, replication lag) alongside PostgreSQL, MongoDB, and Kafka.

## When This Decision Would Be Wrong

This choice should be revisited if session/rate-limit traffic volume grows to the point where a single Redis cluster becomes a shared-fate bottleneck across unrelated services (e.g., Finance cache pressure causing evictions that degrade Identity session lookups) — at that point, splitting into dedicated Redis clusters per concern (or per criticality tier) is the right fix, not abandoning Redis. It would also be wrong if a future compliance requirement mandated durable, auditable session/rate-limit history (e.g., regulatory session audit trails), since Redis's persistence model is not designed for that; a hybrid with an async durable log (Kafka topic to a database) would be needed instead. Finally, if the team's operational capacity shrinks such that running and monitoring a stateful clustered service becomes unsustainable, a managed Redis-compatible cloud offering (not a different technology) is the appropriate mitigation before reconsidering the decision itself.

## How We Will Validate It

- Load test the API Gateway's rate limiter with a synthetic burst (e.g., 10k req/s sustained for 60s across 5 gateway instances) and verify the aggregate accepted/rejected counts match the configured limit within 1% error, confirming cross-instance consistency.
- Benchmark p99 latency added by a Redis round trip on the session-validation path under realistic concurrent load; target p99 < 5ms for GET on session keys, measured via OpenTelemetry spans tagged `redis.session.lookup`.
- Measure Finance Service cache hit rate in staging under representative read traffic; target > 80% hit rate on account-summary endpoints, with a Grafana panel alerting if hit rate drops below 50% (signal of a TTL or invalidation bug).
- Run a chaos test that kills the Redis primary during active load and confirm the Gateway and Identity Service fail closed as designed (reject rather than silently allow), with recovery and session-loss impact captured in the incident runbook.