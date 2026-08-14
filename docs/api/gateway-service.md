# gateway-service API

Base URL (local): `http://localhost:8080`

Management URL (local): `http://localhost:9080`

The gateway is the single public REST ingress. It exposes only the finite route prefixes configured
under `gateway.routes`; it never turns a caller-provided path, host, or header into a proxy target.
The initial route table is:

| Public prefix | Upstream | Authentication |
| --- | --- | --- |
| `/api/v1/accounts` | `LIFEOS_GATEWAY_IDENTITY_UPSTREAM` | `GET`/`HEAD` gateway enforced; registration `POST` is public |
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

## Authentication contract

Routes require authentication by default. Mixed routes can protect only selected methods, so account
registration remains public while account lookup is protected. The `/api/v1/auth` bootstrap prefix
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

The gateway authenticates itself to identity-service with `X-LifeOS-Workload-Identity` and
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
| `413 Payload Too Large` | `PAYLOAD_TOO_LARGE` | Request exceeds its configured bound |
| `502 Bad Gateway` | `UPSTREAM_UNAVAILABLE` | Upstream cannot be reached, returns an unusable transport response, or exceeds its response-size bound |
| `504 Gateway Timeout` | `UPSTREAM_TIMEOUT` | Upstream connection or response read exceeds its deadline |

Failures use RFC 9457 problem details with generic client-safe text. Upstream application responses
such as `401`, `403`, `409`, or `500` are otherwise passed through unchanged so the public contract
remains owned by the domain service.

## Configuration and limits

`IDENTITY_GATEWAY_WORKLOAD_TOKEN` must be configured independently in gateway and identity-service
deployments. `LIFEOS_GATEWAY_MAX_REQUEST_BODY_BYTES` defaults to 1 MiB and
`LIFEOS_GATEWAY_MAX_RESPONSE_BODY_BYTES` defaults to 10 MiB. Connection and read timeouts default to
2 seconds and 5 seconds and are bounded to 60 seconds. Each route's upstream must be an absolute
HTTP(S) origin without userinfo, query, fragment, or a base path; duplicate route IDs and prefixes
fail startup.

Authentication validation is O(1) remote calls per protected request and adds no unbounded gateway
state. Request/response buffering remains bounded by the configured byte limits; downstream
object-level authorization, rate limiting, and circuit breaking remain separate service or later
Epic 2 responsibilities. The gateway-side identity bulkhead provides a bounded concurrency guard
for that dependency; rejected capacity is observable as `identity_bulkhead_rejected`.

## Operational guardrails

The gateway exposes the Micrometer gauge `gateway.inflight.requests` through its Prometheus
endpoint. Before production traffic, configure a heap-pressure alert such as
`sum(jvm_memory_used_bytes{area="heap"}) / sum(jvm_memory_max_bytes{area="heap"}) > 0.85`
for five minutes, and page or shed traffic when `gateway_inflight_requests` approaches the
deployment's concurrency budget. The response buffer is bounded per request, but aggregate heap
use still scales with the configured response limit and concurrent in-flight requests; a future
bulkhead will provide a stricter aggregate bound.
