# ADR-022: Gateway rate limiting and dependency isolation

## Status

Accepted and implemented in `services/gateway-service`.

## Context

The gateway is the public ingress for horizontally scaled LifeOS services. A local counter would
allow a client to bypass limits by changing gateway instances, while unbounded waiting on a slow
upstream would turn dependency failure into gateway-wide resource exhaustion. The gateway therefore
needs one shared admission decision and bounded per-route dependency capacity.

## Decision

Use Redis for a fixed-window request counter. One Lua script performs `INCR` and applies `PEXPIRE`
only to the first request, making the counter atomic across gateway instances. The key contains a
digest of the deployment-owned route ID plus the immediate client address for anonymous requests
or the validated account ID for authenticated requests. Protected requests receive both a higher
address-based pre-authentication charge and an account-based post-authentication charge; public
requests receive only the address-based charge. The digest uses HMAC-SHA-256 with the mandatory
secret-manager supplied key; there is no unkeyed development fallback. The script returns the
remaining window TTL so rejected clients receive precise reset guidance.

Apply a non-waiting semaphore bulkhead and a consecutive-failure circuit breaker independently to
each configured route. Transport errors, timeouts, oversized upstream responses, and upstream 5xx
responses count as dependency failures. A full bulkhead or open circuit returns a generic `503`
with bounded retry guidance; Redis failure also fails closed with `503`. The existing explicit
connection/read timeouts remain the final bound on one admitted upstream call.

For demonstrably replay-safe gateway traffic, retry transient transport failures and upstream
`408`, `500`, `502`, `503`, and `504` responses with a bounded, capped exponential full-jitter
policy. Automatic replay is deliberately restricted to `GET`, `HEAD`, and `OPTIONS`; it excludes
`POST`, `PUT`, `PATCH`, and `DELETE`, even when callers provide an idempotency header, because the
gateway cannot establish that each downstream operation implements the corresponding idempotency
contract. The domain service that owns that contract owns write retries. The route bulkhead remains
held for the whole logical invocation, including bounded backoff, and the circuit records only its
final outcome. Before retrying, reserve the worst-case delay plus a full connection-and-read attempt
from a finite total deadline; do not start a retry when that reservation does not fit.

## Consequences

- Rate-limit state is consistent across gateway instances and expires automatically, but Redis is a
  runtime dependency for public admission.
- Redis failures can reject otherwise valid traffic. This is deliberate: fail-open behavior would
  remove the abuse protection during the dependency incident.
- Bulkhead and circuit state is local to one gateway instance. It is bounded operational state, not
  authorization state; each instance protects its own resources independently.
- Request and response buffering use independent gateway-wide admission counts and aggregate byte
  budgets. Response admission is held through the downstream client write, while the per-route
  bulkhead independently bounds active upstream work; neither path has an unbounded wait queue.
- Automatic retries can add bounded latency to safe reads, but cannot replay a proxied mutation or
  create work outside the existing route bulkhead. A retry that cannot fit the remaining total
  deadline returns the preceding dependency outcome rather than risking deadline overrun.
- Metrics use route-only labels. Account IDs, addresses, tokens, Redis keys, and free-form request
  input are excluded from metrics and logs.

## Observability

The gateway exposes configured rate budgets, allowed/rejected/unavailable rate-limit counters, and
rate-limit latency timers. Upstream latency, failures, bulkhead rejections, circuit-open
rejections, response-buffer capacity rejections, retry-attempt counters, and retry-skip counters
are also emitted with route-only labels. Retry counters distinguish transient response versus
transport retries and unsafe-method, attempt-limit, total-deadline, or interruption skip reasons.
Management endpoints remain on the private management listener and Prometheus scraping should alert
on Redis failures, circuit-open events, bulkhead saturation, response-buffer capacity rejection,
and elevated p95/p99 dependency latency.

## Validation

Unit tests cover atomic-counter decisions, fail-closed Redis failures, privacy-safe keys, bulkhead
rejection, consecutive-failure opening, half-open probing, and permit release. Integration coverage
also exercises safe-read retry recovery, transport retry recovery, mutation non-replay, deadline
reservation, interruption, timeout, payload, and response-contract behavior.
