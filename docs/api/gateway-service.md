# gateway-service API

Base URL (local): `http://localhost:8080`

Management URL (local): `http://localhost:9080`

The gateway is the single public REST ingress. It exposes only the finite route prefixes configured
under `gateway.routes`; it never turns a caller-provided path, host, or header into a proxy target.
The initial route table is:

| Public prefix | Upstream | Status |
| --- | --- | --- |
| `/api/v1/accounts` | `LIFEOS_GATEWAY_IDENTITY_UPSTREAM` | enabled |
| `/api/v1/auth` | `LIFEOS_GATEWAY_IDENTITY_UPSTREAM` | enabled |
| `/api/v1/goals` | `LIFEOS_GATEWAY_TASK_GOAL_UPSTREAM` | enabled |

The gateway preserves the request method, path, query string, body, content type, authorization,
and ordinary application headers. It preserves upstream status, response body, and public response
headers while stripping hop-by-hop headers and any downstream attempt to replace the gateway's
correlation ID. Internal identity routes such as `/api/v1/internal/**` are not public gateway routes.

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
| `413 Payload Too Large` | `PAYLOAD_TOO_LARGE` | Request or buffered upstream response exceeds its configured bound |
| `502 Bad Gateway` | `UPSTREAM_UNAVAILABLE` | Upstream cannot be reached or returns an unusable transport response |
| `504 Gateway Timeout` | `UPSTREAM_TIMEOUT` | Upstream connection or response read exceeds its deadline |

Failures use RFC 9457 problem details with generic client-safe text. Upstream application responses
such as `401`, `403`, `409`, or `500` are otherwise passed through unchanged so the public contract
remains owned by the domain service.

## Configuration and limits

`LIFEOS_GATEWAY_MAX_REQUEST_BODY_BYTES` defaults to 1 MiB and
`LIFEOS_GATEWAY_MAX_RESPONSE_BODY_BYTES` defaults to 10 MiB. Connection and read timeouts default to
2 seconds and 5 seconds and are bounded to 60 seconds. Each route's upstream must be an absolute
HTTP(S) origin without userinfo, query, fragment, or a base path; duplicate route IDs and prefixes
fail startup.

The gateway currently implements routing and correlation only. Authentication enforcement, rate
limiting, circuit breaking, and bulkhead isolation are subsequent Epic 2 stories.
