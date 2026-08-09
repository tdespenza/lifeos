# identity-service API

Base URL (local): `http://localhost:8081`

Management URL (local): `http://localhost:9081`

Status: account registration and first-party email/password login are implemented. OAuth2/OIDC, passkeys/WebAuthn, refresh-token rotation, asymmetric key/JWKS publication, RBAC/ABAC, and user-facing session revocation remain planned stories. The target authentication and session design is documented in [ADR-020](../adr/ADR-020-use-identity-service-for-multi-mode-authentication-and-session-management.md); `UserAccount` deliberately does not store credentials, which are owned by the separate `PasswordCredential` entity.

The story-level login sequence, domain, and lifecycle diagrams are in
[`docs/diagrams/identity-login.md`](../diagrams/identity-login.md).

All requests receive a server-generated `X-Correlation-ID` response header. Any incoming value is ignored so caller-controlled personal data cannot enter MDC, request context, or structured logs. Registration logs include the generated correlation context and event outcome without logging the email address, account identifier, or database exception details.

## `POST /api/v1/auth/login`

Authenticate a registered account with its first-party email and password. The identity service
normalizes the email by trimming and lower-casing with `Locale.ROOT`, checks the Redis-backed
distributed attempt limiter before loading credentials, verifies an Argon2id hash, and creates a
durable PostgreSQL session through the shared ADR-020 authority. A Redis timeout or command failure
fails closed: credentials are not evaluated and no session is issued.

### Request Body

```json
{
  "email": "ada@example.com",
  "password": "correct horse battery staple"
}
```

The password is accepted only for the duration of the request and is never persisted, returned, or
logged. Accounts without an explicitly provisioned password credential use the same generic failure
path as unknown accounts.

### Responses

| Status | Condition | Body/headers |
| --- | --- | --- |
| `200 OK` | Active account and active password credential verified | `LoginResponse` containing `sessionId`, signed `accessToken`, `tokenType: Bearer`, and `expiresIn` seconds |
| `400 Bad Request` | Missing, malformed, or invalidly shaped input | Generic RFC 9457 problem detail; values are not echoed |
| `401 Unauthorized` | Unknown email, missing credential, wrong password, disabled account, or disabled credential | Same generic RFC 9457 problem detail for every credential failure |
| `409 Conflict` | Account active-session capacity reached | Generic problem detail; no session is created |
| `429 Too Many Requests` | Redis limiter threshold exceeded | Generic problem detail plus `Retry-After` seconds |
| `503 Service Unavailable` | Redis limiter, audit persistence, session lookup/persistence, JWT encoding, or another required authentication dependency cannot complete safely | Generic temporary-failure problem detail; credentials are not evaluated when the limiter fails |

### Example Response (200)

```json
{
  "sessionId": "d49f7cc3-78d4-4d68-8abd-b76fb3d8a77d",
  "accessToken": "<signed JWT, redacted>",
  "tokenType": "Bearer",
  "expiresIn": 300
}
```

Story 1.2 uses the shared session/token authority and an externally supplied HS256 signing secret
for the short-lived access token. Story 1.5 owns refresh-token rotation, asymmetric key rotation,
JWKS publication, and downstream verification hardening; it must extend this authority rather than
introduce another response or token format. Set `IDENTITY_JWT_SIGNING_SECRET` to at least 32 bytes
through a secrets-manager-backed deployment configuration. The local/test profile provides only a
test secret.

Login attempts are limited to five attempts per 60-second Redis window by default. Limiter keys are
HMAC-SHA-256 digests of normalized email plus the request source address, using the dedicated
`IDENTITY_RATE_LIMIT_KEY_SECRET`; raw values never enter Redis, logs, metrics, or audit events. Audit
client fingerprints use a separate `IDENTITY_AUDIT_CLIENT_FINGERPRINT_SECRET`. Both secrets must be
supplied by a secret manager and must not reuse the JWT signing key. The account's active-session
limit is ten by default.

## `POST /api/v1/accounts`

Register a new account.

### Request Body

```json
{
  "email": "ada@example.com",
  "displayName": "Ada Lovelace"
}
```

`email` must be non-blank and a valid email address; `displayName` must be non-blank (enforced via Jakarta Bean Validation on [`RegisterAccountRequest`](../../services/identity-service/src/main/java/com/lifeos/identity/account/dto/RegisterAccountRequest.java)).

### Responses

| Status | Condition | Body |
| --- | --- | --- |
| `201 Created` | Account created | `AccountResponse` (see below), `Location` header set to `/api/v1/accounts/{id}` |
| `400 Bad Request` | Validation failure (blank/invalid email or displayName) | Generic RFC 9457 problem detail; field values are not echoed |
| `409 Conflict` | An account already exists for that email, including a concurrent uniqueness conflict | Generic plain-text message; does not echo the email or database details |

### Example Response (201)

```json
{
  "id": "d49f7cc3-78d4-4d68-8abd-b76fb3d8a77d",
  "email": "ada@example.com",
  "displayName": "Ada Lovelace",
  "createdAt": "2026-07-31T04:10:02.468067Z"
}
```

## `GET /api/v1/accounts/{id}`

Fetch an account by id.

### Responses

| Status | Condition | Body |
| --- | --- | --- |
| `200 OK` | Found | `AccountResponse` |
| `404 Not Found` | No account with that id | Plain-text message |

## Operational endpoints

Spring Boot Actuator endpoints exposed by the service:

| Endpoint | Purpose |
| --- | --- |
| `GET http://localhost:9081/actuator/health` | Overall service health; database details are not included in the response |
| `GET http://localhost:9081/actuator/health/liveness` | Process liveness probe |
| `GET http://localhost:9081/actuator/health/readiness` | Readiness probe, including database availability |
| `GET http://localhost:9081/actuator/prometheus` | Prometheus-compatible request and application metrics |

Actuator runs on a separate management listener bound to loopback by default. Set `IDENTITY_MANAGEMENT_PORT` and `IDENTITY_MANAGEMENT_ADDRESS` for a private deployment interface, and keep that listener behind the deployment network boundary. Only the endpoints above plus `info` are exposed (see [`application.yml`](../../services/identity-service/src/main/resources/application.yml)) — no `/actuator/env`, `/actuator/beans`, etc. are exposed, to avoid leaking configuration details. HTTP observations are traced through the configured Micrometer/OpenTelemetry bridge and exported to the OTLP endpoint configured by `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT`.

## Data store

PostgreSQL, database `lifeos_identity`, with these identity-owned tables:

| Table | Purpose |
| --- | --- |
| `user_account` | Canonical account identity, status, and registration metadata; email remains protected by `uk_user_account_email`. |
| `password_credential` | One Argon2id encoded first-party credential per account; no raw password. |
| `auth_session` | Durable session metadata and SHA-256 access-token digest for revocation authority. |
| `security_audit_event` | Redacted authentication outcomes and correlation metadata. |

See [`UserAccount`](../../services/identity-service/src/main/java/com/lifeos/identity/account/UserAccount.java), [`PasswordCredential`](../../services/identity-service/src/main/java/com/lifeos/identity/auth/PasswordCredential.java), and [`AuthSession`](../../services/identity-service/src/main/java/com/lifeos/identity/auth/AuthSession.java). Schema is currently managed by Hibernate's `ddl-auto: update` — this is a known Phase 1 shortcut; a real migration tool (e.g. Flyway) should replace it before this goes further, per the persistence-model tradeoff called out in [ADR-008](../adr/ADR-008-use-postgresql-as-system-of-record.md).

For deployed environments, set `IDENTITY_DATASOURCE_URL`, `IDENTITY_DATASOURCE_USERNAME`, and `IDENTITY_DATASOURCE_PASSWORD` rather than relying on the local-development defaults in `application.yml`.
