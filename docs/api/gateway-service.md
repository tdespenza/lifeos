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
| `/api/v1/tasks` | `LIFEOS_GATEWAY_TASK_GOAL_UPSTREAM` | gateway enforced |
| `/api/v1/dependencies` | `LIFEOS_GATEWAY_TASK_GOAL_UPSTREAM` | gateway enforced |
| `/api/v1/profiles` | `LIFEOS_GATEWAY_PROFILE_UPSTREAM` | gateway enforced |
| `/api/v1/households` | `LIFEOS_GATEWAY_PROFILE_UPSTREAM` | gateway enforced |
| `/api/v1/calendar` | `LIFEOS_GATEWAY_CALENDAR_UPSTREAM` | gateway enforced |
| `/api/v1/finance` | `LIFEOS_GATEWAY_FINANCE_UPSTREAM` | gateway enforced |
| `/api/v1/documents` | `LIFEOS_GATEWAY_DOCUMENT_VAULT_UPSTREAM` | gateway enforced; exact `POST /api/v1/documents` is the sole request-streaming multipart exception |
| `/api/v1/media/assets` | `LIFEOS_GATEWAY_MEDIA_UPSTREAM` | gateway enforced; only exact canonical-asset source `PUT` and HLS manifest/segment `GET` operations use their isolated relay exceptions |
| `/api/v1/media/sessions` | `LIFEOS_GATEWAY_MEDIA_UPSTREAM` | gateway enforced; ordinary bounded buffered HTTP |
| `/api/v1/assistant` | `LIFEOS_GATEWAY_AI_ASSISTANT_UPSTREAM` | gateway enforced; bounded JSON/GET HTTP with an isolated 12-second upstream deadline |
| `/api/v1/trust` | `LIFEOS_GATEWAY_TRUST_LEDGER_UPSTREAM` | gateway enforced; bounded proof/anchor HTTP, never a generic provider tunnel |
| `/api/v1/analytics` | `LIFEOS_GATEWAY_ANALYTICS_UPSTREAM` | gateway enforced; ordinary bounded dashboard JSON with an optional HMAC subject proof |
| exact `GET /api/v1/notifications/stream` | `LIFEOS_GATEWAY_NOTIFICATION_UPSTREAM` | gateway enforced; the sole SSE byte-streaming exception |
| `/api/v1/notifications` | `LIFEOS_GATEWAY_NOTIFICATION_UPSTREAM` | gateway enforced; ordinary bounded buffered HTTP, including history |
| `/api/v1/notification-endpoints` | `LIFEOS_GATEWAY_NOTIFICATION_UPSTREAM` | gateway enforced; ordinary bounded buffered HTTP |

The gateway preserves the request method, path, query string, body, content type, authorization,
and ordinary application headers. It preserves upstream status, response body, and eligible public
response headers while stripping hop-by-hop headers, upstream `Content-Length`, and any downstream
attempt to replace the gateway's correlation ID. For non-`HEAD` responses, the gateway recalculates
`Content-Length` from the bounded response body; `HEAD` responses omit the body and do not add a
replacement length header. Internal identity routes such as `/api/v1/internal/**` are not public
gateway routes.

The Assistant route uses a separate outbound client with a default 2-second connect and 12-second
exchange deadline. This reserves the gateway connection, the service's 3-second Identity call,
5-second provider call, and a 2-second transfer/error margin, so a provider timeout can reach
callers as the service's structured `504` response instead of being converted into a generic
gateway timeout. The route remains buffered and is never automatically retried; the service owns
any safe retry policy.

When `LIFEOS_GATEWAY_ANALYTICS_PROOF_SECRET` is configured, protected downstream requests receive
`X-LifeOS-Gateway-Proof`, an HMAC-SHA256 over method, path, authenticated account, and session.
Analytics requires this proof for its dashboard route; callers cannot supply or override it.

### Notification SSE exception

Only exact `GET /api/v1/notifications/stream` bypasses the ordinary 10 MiB/5-second buffered
response policy. The gateway still authenticates the request, applies the same pre- and
post-authentication rate-limit charges, assigns one correlation ID, forwards the validated subject
headers, and preserves `Last-Event-ID`. It relays upstream bytes through a fixed 8 KiB buffer,
flushes each chunk, strips hop-by-hop and upstream `Content-Length` headers, and retains no event
history or response body. `POST`, `HEAD`, and every path descendant are rejected; no arbitrary
route can opt into streaming.

SSE has a separate non-waiting global admission bound and separate connection/read lifetime. The
exact `notification-stream` route also has its own route ID, circuit, and upstream bulkhead, so
valid long-lived streams cannot consume the buffered notification-history or endpoint route
capacity. A downstream disconnect closes the upstream input stream; an upstream failure after
headers are committed ends the stream and the client reconnects with `Last-Event-ID`. The gateway
does not retry live streams because automatic replay could duplicate or reorder an event; the
notification service owns bounded replay and the client owns reconnect.

### Document Vault multipart exception

Only exact authenticated `POST /api/v1/documents` bypasses the ordinary 1 MiB inbound request
buffer. The gateway preserves the multipart `Content-Type` boundary and a declared
`Content-Length` when present, then copies the request from the servlet input stream to the fixed
Document Vault upstream through one 8 KiB relay buffer. It checks both declared and actual bytes
against a hard 11,010,048-byte ceiling (the reviewed 10 MiB file maximum plus multipart framing
allowance); chunked or dishonest-length requests cannot bypass this check. A request that exceeds
the actual bound is stopped before its over-limit read is written upstream.

This is a narrow request-side exception, not an arbitrary streaming tunnel.
`/api/v1/documents/{id}` and all non-`POST` document operations retain the ordinary buffered proxy
policy. The route has a fair, non-waiting admission limit (default 8 per gateway instance), the
usual distinct per-route bulkhead/circuit state, finite upload client deadlines (default 2 seconds
to connect and 45 seconds to finish), and a normally bounded downstream response. The route is
authenticated and rate-limited before the input stream is opened. The gateway never retries
document uploads, even after a transient response or transport failure: only Document Vault can
determine durable idempotency.

### Media source and HLS exceptions

The Media service has three separately reviewed high-volume operations. They do not turn either
`/api/v1/media/assets` or `/api/v1/media/sessions` into a generic streaming proxy:

- Exact `PUT /api/v1/media/assets/{canonical-uuid}/source` relays its multipart body through one
  fixed 8 KiB buffer. It preserves multipart `Content-Type` and a declared `Content-Length` when
  present, rejects declared or actual request bodies over 53,477,376 bytes (51 MiB), and never
  automatically retries. Its default 75-second upstream deadline deliberately leaves a finite
  transfer margin over Media's 60-second application deadline.
- Exact `GET /api/v1/media/assets/{canonical-uuid}/hls/master.m3u8` and exact
  `GET /api/v1/media/assets/{canonical-uuid}/hls/segments/{segmentName}` relay the upstream
  response through a fixed 16 KiB buffer without taking a normal response-buffer permit. The
  `segmentName` must be at most 128 safe characters, contain no `..`, and end in `.m4s` or `.ts`.
  Both routes have a 25 MiB hard response bound and a finite 60-second upstream read deadline.
- Every other Media request—including a noncanonical source path, an invalid HLS path, and all
  JSON/session operations—uses the ordinary 1 MiB request and 10 MiB response proxy limits.

Media source uploads and HLS reads each have an independent fair, non-waiting admission semaphore
and a distinct virtual circuit/bulkhead state from ordinary Media metadata/session traffic. This
prevents long transfer work from consuming the small-control-plane route's resilience budget.
An HLS upstream with a declared content length above the hard limit receives a controlled `502`
before headers are sent. If a chunked upstream exceeds the hard limit after some bytes are already
committed, the gateway ends the response, records a route-only limit violation, and never buffers
the segment to manufacture a late error response. HLS clients obtain a fresh manifest/segment; the
gateway never retries a partial relay.

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
`gateway.rate.limit` (configured budget, with `stage=address` for pre-authentication and public
admission or `stage=account` after subject validation), `gateway.rate.limit.allowed`,
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
- Every gateway outbound HTTP client, including Identity validation and the isolated streaming and
  upload clients, injects the active W3C `traceparent`/`tracestate` context with the standard
  OpenTelemetry propagator. A downstream service therefore receives the current child span rather
  than a caller-supplied stale trace header; the propagation integration test exercises the
  Identity hop.

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
| `503 Service Unavailable` | `STREAMING_CAPACITY` | Bounded gateway-wide live-SSE connection admission is full; retry later |
| `503 Service Unavailable` | `DOCUMENT_UPLOAD_CAPACITY` | Bounded gateway-wide Document Vault upload-relay admission is full; retry later |
| `503 Service Unavailable` | `MEDIA_UPLOAD_CAPACITY` | Bounded gateway-wide Media source-upload relay admission is full; retry later |
| `503 Service Unavailable` | `MEDIA_HLS_CAPACITY` | Bounded gateway-wide Media HLS response-stream admission is full; retry later |
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
Ordinary outbound connection and read timeouts default to 2 seconds and 5 seconds and are bounded to 60
seconds. `LIFEOS_GATEWAY_INBOUND_REQUEST_TIMEOUT` defaults to 10 seconds (bounded from 1 ms to 60
seconds) and is applied to Tomcat request-line/header reads, idle keep-alive sockets, and
request-body uploads. Spring Boot does not expose Tomcat's separate upload timeout directly, so
the gateway enables it through a connector customizer instead of accepting Tomcat's five-minute
default. A client that stops making body-read progress therefore cannot retain a request-body
buffer permit indefinitely.

The Assistant exception is configured separately by
`LIFEOS_GATEWAY_AI_ASSISTANT_CONNECT_TIMEOUT` (default 2 seconds) and
`LIFEOS_GATEWAY_AI_ASSISTANT_READ_TIMEOUT` (default 12 seconds). Startup requires the latter to
cover its gateway connection plus the service's three-second Identity validation, five-second
provider deadline, and a two-second transfer/error margin. This lets the assistant return its
structured provider-timeout response instead of having the gateway preempt it with a generic 504.

The SSE exception is configured separately: `LIFEOS_GATEWAY_STREAMING_MAX_CONCURRENT_CONNECTIONS`
defaults to 32 per gateway instance, `LIFEOS_GATEWAY_STREAMING_CONNECT_TIMEOUT` defaults to 2
seconds (maximum 60 seconds), and `LIFEOS_GATEWAY_STREAMING_READ_LIFETIME` defaults to 30 minutes
(maximum one hour). This is a finite connection lifetime, not an unbounded idle timeout; clients
must reconnect using `Last-Event-ID`. Startup rejects any streaming configuration that is not the
authenticated exact `GET /api/v1/notifications/stream` policy.

The Document Vault request exception is separately configured by
`LIFEOS_GATEWAY_DOCUMENT_UPLOAD_MAX_CONCURRENT_UPLOADS` (default 8),
`LIFEOS_GATEWAY_DOCUMENT_UPLOAD_MAX_REQUEST_BODY_BYTES` (default and hard maximum 11,010,048),
`LIFEOS_GATEWAY_DOCUMENT_UPLOAD_CONNECT_TIMEOUT` (default 2 seconds), and
`LIFEOS_GATEWAY_DOCUMENT_UPLOAD_READ_TIMEOUT` (default 45 seconds, maximum 60 seconds). Startup
rejects a document-streaming route unless it is the authenticated `/api/v1/documents` prefix with
no mixed-public or method exception; the forwarder separately allows only its exact `POST` create
operation. The configured request size includes multipart framing and must remain compatible with
Document Vault's `DOCUMENT_VAULT_MAX_INBOUND_BODY_BYTES` setting.

The Media exceptions are separately configured by
`LIFEOS_GATEWAY_MEDIA_UPLOAD_MAX_CONCURRENT_UPLOADS` (default 4),
`LIFEOS_GATEWAY_MEDIA_UPLOAD_MAX_REQUEST_BODY_BYTES` (default and hard maximum 53,477,376),
`LIFEOS_GATEWAY_MEDIA_UPLOAD_CONNECT_TIMEOUT` (default 2 seconds), and
`LIFEOS_GATEWAY_MEDIA_UPLOAD_READ_TIMEOUT` (default 75 seconds, bounded to 120 seconds and never
less than 60 seconds). The HLS response relay uses
`LIFEOS_GATEWAY_MEDIA_HLS_MAX_CONCURRENT_STREAMS` (default 8),
`LIFEOS_GATEWAY_MEDIA_HLS_MAX_RESPONSE_BODY_BYTES` (default and hard maximum 26,214,400),
`LIFEOS_GATEWAY_MEDIA_HLS_CONNECT_TIMEOUT` (default 2 seconds), and
`LIFEOS_GATEWAY_MEDIA_HLS_READ_TIMEOUT` (default 60 seconds). Startup rejects a Media relay flag
unless it is the authenticated `/api/v1/media/assets` prefix with no mixed-public or method
exception; the forwarder independently rechecks the exact method, canonical asset UUID, and HLS
path before bypassing ordinary buffering.

Each route must use a named versioned public prefix of the form `/api/v<positive-integer>/<resource>`;
the root path and a version-level catch-all are rejected at startup. Each route's upstream must be
an absolute HTTPS origin without userinfo, query, fragment, or a base path. Cleartext HTTP is
accepted only for a local loopback development endpoint (for example, `localhost`, a `127.0.0.0/8`
address, or `[::1]`);
remote HTTP origins fail startup. Duplicate route IDs and prefixes also fail startup.
`LIFEOS_GATEWAY_RATE_LIMIT_MAX_REQUESTS` defaults to 600 per account per
`LIFEOS_GATEWAY_RATE_LIMIT_WINDOW`; `LIFEOS_GATEWAY_RATE_LIMIT_PRE_AUTHENTICATION_MAX_REQUESTS`
defaults to 6000 per address per window and must not be lower than the account budget. The
`LIFEOS_GATEWAY_RATE_LIMIT_WINDOW` defaults to one minute. `LIFEOS_GATEWAY_RATE_LIMIT_KEY_SECRET` is a
required HMAC key supplied by secret management; the gateway fails startup when it is absent or
blank. Redis connect and command timeouts default to 500 milliseconds.

Each route has a 64-request non-waiting bulkhead by default. Five consecutive transport, timeout,
oversized-response, or upstream-5xx failures open that route's circuit for 10 seconds, after which
one half-open probe is admitted. Bulkhead rejections and circuit-open responses include a bounded
`Retry-After` value and do not wait for a slow upstream.

The gateway retries only transient upstream `408`, `500`, `502`, `503`, and `504` responses and
transport failures for generic `GET`, `HEAD`, and `OPTIONS` routes. The Assistant route is always
single-attempt because the gateway cannot prove that a nominally safe method is free of provider or
tool work. It never automatically replays `POST`, `PUT`, `PATCH`, or `DELETE`—including when a
caller sends an `Idempotency-Key`—because the gateway cannot prove an arbitrary domain endpoint's
idempotency semantics. A service that explicitly owns an idempotency contract must own retries for
its write operations. `max-attempts` includes the initial
call and defaults to 2. `LIFEOS_GATEWAY_UPSTREAM_RETRY_INITIAL_BACKOFF` (100 ms) and
`LIFEOS_GATEWAY_UPSTREAM_RETRY_MAX_BACKOFF` (1 s) form a capped exponential full-jitter delay;
`LIFEOS_GATEWAY_UPSTREAM_RETRY_TOTAL_TIMEOUT` defaults to 15 seconds. The total timeout is bounded
to 60 seconds and must contain at least one full connection-plus-read attempt. Before a retry, the
gateway reserves the worst-case jitter cap plus another full connection-plus-read attempt; if the
remaining total budget cannot contain both, it returns the preceding upstream outcome without
starting another call. The same route bulkhead permit covers the complete logical retry sequence,
and the circuit records its final logical result once, so automatic retries cannot bypass
admission limits or multiply circuit failures.

Authentication validation is O(1) remote calls per protected request and adds no unbounded gateway
state. Redis rate-limit state is bounded by counter TTLs. Inbound request buffering is bounded by
the configured byte limit, aggregate byte budget, and independent gateway-wide body-buffer admission
semaphore. Downstream response buffering is bounded by its byte limit, aggregate byte budget, and
the independent gateway-wide response-buffer admission semaphore held through client writes.
The SSE exception instead has its own fair connection semaphore, an 8 KiB relay buffer per live
connection, and a finite upstream request lifetime; it never borrows a response-buffer permit.
The Document Vault exception has its own fair upload semaphore and one 8 KiB request relay buffer
per in-flight upload; it holds a normal response-buffer permit while the request is active so a
completed upstream response can never exceed the gateway's validated response-memory budget.
Media source uploads likewise use an 8 KiB request relay buffer while holding a normal response
buffer permit for their final domain response. Media HLS reads use a separate 16 KiB response relay
buffer and no normal response-buffer permit; their independent 25 MiB transfer ceiling is enforced
incrementally rather than retained in heap.
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
rate-limit metrics. Monitor `gateway.upstream.retry.attempts` (route and transient failure class)
and `gateway.upstream.retry.skipped` (route and bounded skip reason) to distinguish recovered
dependency blips from unavailable, unsafe-to-replay, or deadline-exhausted calls.
For SSE, alert on `gateway.streaming.inflight.connections`,
`gateway.streaming.available.permits`, and `gateway.streaming.capacity.rejections`; capacity is
per instance, so size it with the notification service's per-account stream cap and deployment
replica count.
For Document Vault creation, monitor `gateway.document.upload.inflight`,
`gateway.document.upload.available.permits`, and
`gateway.document.upload.capacity.rejections` alongside the document service's own upload-limit and
storage-failure metrics. Tune the per-instance admission only after confirming the downstream
service, database, and object-store write budgets; increasing it does not raise the fixed 11 MB
request ceiling.
For Media, monitor `gateway.media.upload.inflight`,
`gateway.media.upload.available.permits`, `gateway.media.upload.capacity.rejections`,
`gateway.media.hls.inflight`, `gateway.media.hls.available.permits`,
`gateway.media.hls.capacity.rejections`, and
`gateway.media.hls.response.limit.violations`. The `media-assets-media-upload` and
`media-assets-media-hls` upstream metric route tags are intentionally distinct from the ordinary
`media-assets` tag, so transfer degradation cannot be hidden by metadata/session traffic.

## GraphQL dashboard

`POST /graphql` is the authenticated dashboard aggregation entry point. The gateway accepts only
POST requests with a bounded 64 KiB GraphQL document, applies the same Redis pre-authenticated and
subject rate limits as REST routes, and propagates the bearer token to owner-scoped downstream
reads. The `dashboard(periodDays: Int = 30)` query clamps the period to 1–90 days and returns
explicit `COMPLETE`, `PARTIAL`, or `UNAVAILABLE` source status instead of inventing values when a
Task, Calendar, or Finance dependency is unavailable. Source fan-out is bounded to 100 rows per
downstream response and all rejection responses include the ingress correlation ID.

The versioned internal protobufs live in `contracts/grpc-contracts` and define scoped Task,
Calendar, and Finance metric messages for the internal transport. By default the gateway uses
bounded REST compatibility calls. When `LIFEOS_GATEWAY_DASHBOARD_GRPC_ENABLED=true` and all three
service mTLS/workload credentials are supplied, the resolver switches to the gRPC contracts,
sends only the validated personal account UUID plus the `personal` tenant, and applies a bounded
per-source deadline. It never forwards the end-user bearer token to gRPC. Household aggregation
requires a future explicit tenant selector and policy descriptor.
