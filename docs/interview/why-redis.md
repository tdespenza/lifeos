# Why Redis (Caching, Sessions, Rate Limiting)

Identity-service now uses Redis for distributed authentication rate limiting, OAuth2/OIDC callback
state, and single-use WebAuthn assertion challenges. Redis stores only short-lived, bounded state;
PostgreSQL remains the durable store for accounts, credentials, sessions, refresh-token families,
and audit events. Gateway route/client limits and the identity session-revocation cache are already
implemented with fail-closed behavior; shared domain caching remains planned. I want to be
explicit about that boundary instead of implying the full target architecture is wired in.

So why is it there at all? Because I know what's coming, and I'd rather stand the dependency up early and get comfortable operating it before I actually need it under pressure. Once there's an API gateway and an Identity Service doing real session/token validation, and a Finance Service serving account summaries that are expensive to recompute per request, I'll have three needs that all share the same shape: high-churn, latency-sensitive state that every service instance needs to see immediately, and that doesn't need to survive a restart.

I picked Redis over the alternatives for pretty concrete reasons. An in-process cache like Caffeine falls apart the moment you run more than one instance of a service — each instance gets its own view of rate-limit counters or session state, which is disqualifying for anything gateway-facing. Memcached is a fine distributed cache, but it doesn't give you atomic `INCR`-with-TTL or sorted sets for sliding-window rate limiting, or `SET NX` for distributed locks — you'd end up bolting a coordination layer on top of it, which is most of what Redis gives you for free. And I didn't want to route that write volume through Postgres either — rate-limit counters and session churn are exactly the kind of high-frequency, disposable writes I don't want competing for connections and lock time with the financial and identity data that actually needs durability and ACID guarantees.

The honest tradeoff is that Redis becomes a new stateful dependency I have to run, monitor, and keep available — and because it's in-memory by default, a hard restart can reopen the login rate-limit window. Current sessions remain durable in PostgreSQL; only the short-lived revocation marker and challenge state are restart-sensitive. That's an accepted tradeoff for this use case, not an oversight: disposable coordination state needs to be fast and consistent across instances while it's alive.

The next consumers are expected to be domain-specific shared caches, but they must follow the
identity-service session authority and explicit fail-closed semantics defined by ADR-020 rather
than moving durable session state into Redis.

Relevant ADRs: [ADR-010](../adr/ADR-010-use-redis-for-cache-and-rate-limits.md)
