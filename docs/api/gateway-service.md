# gateway-service API

Base URL (local): `http://localhost:8080`

Management URL (local): `http://localhost:9080`

The gateway is the single public REST ingress. It exposes only the finite route prefixes configured
under `gateway.routes`; it never turns a caller-provided path, host, or header into a proxy target.
The initial route table is:

| Public prefix | Upstream | Authentication |
| --- | --- | --- |
| `/api/v1/accounts` | `LIFEOS_GATEWAY_IDENTITY_UPSTREAM` | exact `POST /api/v1/accounts` registration is public; all other account operations are gateway enforced |
| `/api/v1/auth` | `LIFEOS_GATEWAY_IDENTITY_UPSTREAM` | bootstrap operations remain public where identity allows |
| `/api/v1/auth/sessions` | `LIFEOS_GATEWAY_IDENTITY_UPSTREAM` | gateway enforced |
| `/api/v1/goals` | `LIFEOS_GATEWAY_TASK_GOAL_UPSTREAM` | gateway enforced |

The gateway preserves the request method, path, query string, body, content type, authorization,
and ordinary application headers. It preserves upstream status, response body, and eligible public
response headers while stripping hop-by-hop headers, upstream `Content-Length`, and any downstream
attempt to replace the gateway's correlation ID. For non-`HEAD` responses, the gateway recalculates
`Content-Length` from the bounded response body; `HEAD` responses omit the body and do not add a
replacement length header. Internal identity routes such as `/api/v1/internal/**` are not public
gateway routes.

## Rate limiting

Every resolved route is independently rate-limited by Redis fixed-window counters. Protected
requests receive a higher pre-authentication charge to the immediate client address before identity
validation, then a second charge to the validated account ID after successful authentication;
public requests receive only the address charge. Redis keys contain only a route-and-client digest,
never a raw address, account ID, bearer token, or request path. `INCR`, the first-request
`PEXPIRE`, and the remaining TTL are returned by one Lua script, so rejection retry guidance tracks
the actual window reset. Redis failures fail closed with
`503 RATE_LIMITER_UNAVAILABLE` rather than falling back to divergent per-instance counters.

Anonymous rate-limit keys use the servlet peer address, and the gateway deliberately strips
caller-supplied forwarding headers. The public ingress must therefore preserve the actual client
peer address to the gateway, such as through direct L4 pass-through or a trusted PROXY protocol
integration. If a load balancer or CDN terminates the connection and presents one shared peer
address, all anonymous traffic will share one bucket; deployments must correct that topology before
exposing the gateway publicly rather than trusting arbitrary `X-Forwarded-For` values.

Rejected requests return `429 RATE_LIMIT_EXCEEDED` with `Retry-After`, `RateLimit-Limit`,
`RateLimit-Remaining: 0`, and `RateLimit-Reset` headers. The gateway exposes the route-only metrics
`gateway.rate.limit` (configured budget), `gateway.rate.limit.allowed`,
`gateway.rate.limit.rejections`, `gateway.rate.limit.unavailable`, and
`gateway.rate.limit.latency` (with percentile histogram buckets); no client identifier is a metric
label.

## Authentication contract

Routes require authentication by default. Mixed routes can expose only explicitly configured exact
operations, so only account registration at `POST /api/v1/accounts` remains public; descendant
account operations and all other account methods are protected. The `/api/v1/auth` bootstrap prefix
explicitly opts out because login, refresh, OIDC, and passkey endpoints have a mixed contract owned
by identity-service; the more-specific `/api/v1/auth/sessions` route is gateway protected. The initial
task-goal route is protected.

For a protected route, the gateway performs one bounded internal `GET /api/v1/auth/validate` call
to identity-service before reading or forwarding the request body. Identity-service validates the
JWT signature, issuer, audience, time window, required `sub` and `session_id` claims, durable
session ownership, access-token digest, and revocation state. A valid result is reduced to the
account ID, session ID, and authentication method. The gateway forwards those facts as trusted
`X-LifeOS-Authenticated-Account-Id`, `X-LifeOS-Authenticated-Session-Id`, and
`X-LifeOS-Authentication-Method` headers. Caller-supplied copies of those headers and workload
credential headers are always removed. The bearer header remains available to downstream services,
which retain responsibility for object-level authorization and may repeat the identity check.

The higher pre-authentication address budget bounds invalid-credential attempts before they reach
identity-service without making shared egress addresses consume the normal per-account budget; the
post-authentication account budget protects each validated account's route traffic. The gateway
authenticates itself to identity-service with `X-LifeOS-Workload-Identity` and
`X-LifeOS-Workload-Token`; the token is supplied by `IDENTITY_GATEWAY_WORKLOAD_TOKEN` and has no
repository default. Non-loopback identity URLs must use HTTPS. Identity connection and read
timeouts are explicit and bounded to 60 seconds. A fair validation bulkhead admits at most
`LIFEOS_GATEWAY_AUTH_MAX_CONCURRENT_VALIDATIONS` (default 64) identity calls per gateway instance;
when full, protected requests fail closed without waiting for an identity timeout.

## Correlation contract

Every request receives exactly one canonical UUID in `X-Correlation-ID`:

- one valid incoming UUID is normalized to lower case and preserved;
- an absent, repeated, malformed, or unsafe value is replaced with a server-generated UUID;
- the same value is written to the response, gateway MDC/`ScopedValue`, and the downstream request;
- identity-service and task-goal-service preserve that valid value and pass it to their own nested
  calls.

The gateway does not log request bodies, credentials, cookies, or upstream exception text. Structured
logs use the correlation ID and route ID, and outbound requests have explicit connection/read
deadlines.

## Controlled failures

| Status | Code | Condition |
| --- | --- | --- |
| `404 Not Found` | `ROUTE_NOT_FOUND` | No configured versioned public route matches the request path |
| `401 Unauthorized` | `AUTHENTICATION_REQUIRED` | Missing, malformed, expired, revoked, or otherwise invalid bearer credential |
| `503 Service Unavailable` | `AUTHENTICATION_UNAVAILABLE` | Identity validation cannot complete safely; the gateway fails closed |
| `429 Too Many Requests` | `RATE_LIMIT_EXCEEDED` | Redis counter exceeds the configured route/client budget |
| `503 Service Unavailable` | `RATE_LIMITER_UNAVAILABLE` | Redis cannot make a safe rate-limit decision; the gateway fails closed |
| `503 Service Unavailable` | `REQUEST_BODY_CAPACITY` | Bounded request-body buffering capacity is full; retry later |
| `503 Service Unavailable` | `RESPONSE_BUFFER_CAPACITY` | Bounded response-buffer capacity is full; retry later |
| `413 Payload Too Large` | `PAYLOAD_TOO_LARGE` | Request exceeds its configured bound |
| `502 Bad Gateway` | `UPSTREAM_UNAVAILABLE` | Upstream cannot be reached, returns an unusable transport response, or exceeds its response-size bound |
| `504 Gateway Timeout` | `UPSTREAM_TIMEOUT` | Upstream connection or response read exceeds its deadline |
| `503 Service Unavailable` | `UPSTREAM_UNAVAILABLE` | The route circuit is open or its non-waiting bulkhead is full |

Failures use RFC 9457 problem details with generic client-safe text. Upstream application responses
such as `401`, `403`, `409`, or `500` are otherwise passed through unchanged so the public contract
remains owned by the domain service.

## Configuration and limits

`IDENTITY_GATEWAY_WORKLOAD_TOKEN` must be configured independently in gateway and identity-service
deployments. `LIFEOS_GATEWAY_MAX_REQUEST_BODY_BYTES` defaults to 1 MiB and
`LIFEOS_GATEWAY_MAX_CONCURRENT_REQUEST_BODY_BUFFERS` defaults to 64. The latter is a global,
non-waiting admission bound for concurrent inbound request-body buffers; a full bound returns a
controlled `503 REQUEST_BODY_CAPACITY`. `LIFEOS_GATEWAY_MAX_REQUEST_BODY_BUFFER_BYTES` defaults
to 64 MiB and must be at least the product of the request-body count and byte limits.
`LIFEOS_GATEWAY_MAX_CONCURRENT_RESPONSE_BUFFERS` defaults to 64 and is a global, non-waiting
admission bound for retained downstream response buffers through client writes; a full bound
returns `503 RESPONSE_BUFFER_CAPACITY`. `LIFEOS_GATEWAY_MAX_RESPONSE_BODY_BYTES` defaults to
10 MiB. `LIFEOS_GATEWAY_MAX_RESPONSE_BUFFER_BYTES` defaults to 640 MiB and must be at least the
product of the response-buffer count and byte limits. Incompatible count, per-buffer, and aggregate
budgets fail startup.
Connection and read timeouts default to 2 seconds and 5 seconds and are bounded to 60 seconds. Each route's upstream must be an absolute
HTTP(S) origin without userinfo, query, fragment, or a base path; duplicate route IDs and prefixes
fail startup. `LIFEOS_GATEWAY_RATE_LIMIT_MAX_REQUESTS` defaults to 600 per account per
`LIFEOS_GATEWAY_RATE_LIMIT_WINDOW`; `LIFEOS_GATEWAY_RATE_LIMIT_PRE_AUTHENTICATION_MAX_REQUESTS`
defaults to 6000 per address per window and must not be lower than the account budget. The
`LIFEOS_GATEWAY_RATE_LIMIT_WINDOW` defaults to one minute. `LIFEOS_GATEWAY_RATE_LIMIT_KEY_SECRET` is a
required HMAC key supplied by secret management; the gateway fails startup when it is absent or
blank. Redis connect and command timeouts default to 500 milliseconds.

Each route has a 64-request non-waiting bulkhead by default. Five consecutive transport, timeout,
oversized-response, or upstream-5xx failures open that route's circuit for 10 seconds, after which
one half-open probe is admitted. Bulkhead rejections and circuit-open responses include a bounded
`Retry-After` value and do not wait for a slow upstream.

Authentication validation is O(1) remote calls per protected request and adds no unbounded gateway
state. Redis rate-limit state is bounded by counter TTLs. Inbound request buffering is bounded by
the configured byte limit, aggregate byte budget, and independent gateway-wide body-buffer admission
semaphore. Downstream response buffering is bounded by its byte limit, aggregate byte budget, and
the independent gateway-wide response-buffer admission semaphore held through client writes.
Downstream object-level authorization remains the domain service's responsibility. The gateway-side
identity bulkhead, request-body admission, response-buffer admission, and upstream route bulkheads
provide bounded concurrency guards for each dependency or buffer class; capacity rejections are
observable through the `gateway.request.body.capacity.rejections` and
`gateway.response.buffer.capacity.rejections` counters and the controlled response contract.

## Operational guardrails

The gateway exposes the Micrometer gauge `gateway.inflight.requests` through its Prometheus
endpoint. Before production traffic, configure a heap-pressure alert such as
`sum(jvm_memory_used_bytes{area="heap"}) / sum(jvm_memory_max_bytes{area="heap"}) > 0.85`
for five minutes, and page or shed traffic when `gateway_inflight_requests` approaches the
deployment's concurrency budget. Monitor `gateway.upstream.latency`, `gateway.upstream.failures`,
`gateway.upstream.bulkhead.rejections`, `gateway.upstream.circuit.open`, the request-body and
response-buffer available-permit gauges, and both buffer-capacity rejection counters alongside the
rate-limit metrics.
