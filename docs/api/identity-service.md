# identity-service API

Base URL (local): `http://localhost:8081`

Management URL (local): `http://localhost:9081`

Status: account registration, first-party email/password login, configured OAuth2/OIDC authorization-code login, and passkey/WebAuthn assertion login are implemented. Passkey credential registration/provisioning, refresh-token rotation, asymmetric key/JWKS publication, RBAC/ABAC, and user-facing session revocation remain planned stories. The target authentication and session design is documented in [ADR-020](../adr/ADR-020-use-identity-service-for-multi-mode-authentication-and-session-management.md); `UserAccount` deliberately does not store credentials, which are owned by separate authentication-boundary entities.

The story-level first-party login diagrams are in
[`docs/diagrams/identity-login.md`](../diagrams/identity-login.md), and the OAuth2/OIDC use-case,
sequence, domain, and lifecycle diagrams are in
[`docs/diagrams/identity-oidc.md`](../diagrams/identity-oidc.md).
The implemented Story 1.4 passkey/WebAuthn use-case, sequence, domain, and lifecycle diagrams are in
[`docs/diagrams/identity-passkey.md`](../diagrams/identity-passkey.md).

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

## `POST /api/v1/auth/passkey/options`

Starts a username-less WebAuthn assertion ceremony. The identity service creates the request using
the configured relying-party id, exact allowed origins, and user-verification policy, stores the
complete assertion request in Redis for the configured short TTL (five minutes by default), and
returns an opaque challenge handle alongside the browser-facing `publicKey` options. The client
passes the `publicKey` object to `navigator.credentials.get` and must keep `challengeId` with the
resulting credential.

### Responses

| Status | Condition | Body/headers |
| --- | --- | --- |
| `200 OK` | Assertion ceremony started | `{ "challengeId": "<opaque 43-character handle>", "publicKey": { ... } }` |
| `429 Too Many Requests` | Client exceeded the shared Redis-backed passkey-attempt limit | Generic problem detail plus `Retry-After` seconds |
| `503 Service Unavailable` | Redis or WebAuthn configuration/dependency cannot complete safely | Generic temporary-failure problem detail |

## `POST /api/v1/auth/passkey/assertion`

Consumes one browser assertion and creates the shared LifeOS session/token result. The service
atomically consumes the server-side challenge before parsing the assertion, then the WebAuthn
library validates the challenge, exact origin, RP-ID hash, user verification, credential public-key
signature, and authenticator counter. The durable `webauthn_credential` record is updated with a
conditional counter write; a stale or concurrent counter update cannot create a session.

### Request Body

```json
{
  "challengeId": "<value returned by the options endpoint>",
  "credential": {
    "id": "<browser credential id>",
    "rawId": "<base64url credential id>",
    "response": {
      "clientDataJSON": "<base64url>",
      "authenticatorData": "<base64url>",
      "signature": "<base64url>",
      "userHandle": "<base64url or null>"
    },
    "type": "public-key",
    "clientExtensionResults": {}
  }
}
```

### Responses

| Status | Condition | Body/headers |
| --- | --- | --- |
| `200 OK` | Valid registered credential and active account | Shared `LoginResponse` containing `sessionId`, signed `accessToken`, `tokenType: Bearer`, and `expiresIn` seconds |
| `400 Bad Request` | Missing, malformed, or invalidly shaped request | Generic RFC 9457 problem detail; assertion values are not echoed |
| `401 Unauthorized` | Unknown/disabled credential, wrong origin or RP ID, invalid signature, missing user verification, stale/replayed challenge, or counter regression | Same generic passkey failure for every assertion rejection |
| `409 Conflict` | Active-session capacity reached | Generic problem detail; no session is created |
| `429 Too Many Requests` | Client exceeded the shared Redis-backed passkey-attempt limit | Generic problem detail plus `Retry-After` seconds |
| `503 Service Unavailable` | A Redis, persistence, audit, or other dependency failure is explicitly mapped to `AuthenticationDependencyUnavailableException` or `DataAccessException` | Generic temporary-failure problem detail |

The identity service never accepts or stores a private key. The authenticator retains the private
key; PostgreSQL stores only the credential id, account/user handle, COSE public key, enabled state,
and signature counter. This story assumes credentials are provisioned by a trusted registration
flow; the registration ceremony and credential-management endpoints are separate scope and must
enforce an authenticated step-up/recovery policy before inserting `webauthn_credential` rows.

Configure `IDENTITY_WEBAUTHN_RP_ID`, `IDENTITY_WEBAUTHN_RP_NAME`,
`IDENTITY_WEBAUTHN_ALLOWED_ORIGINS`, `IDENTITY_WEBAUTHN_USER_VERIFICATION`, and
`IDENTITY_WEBAUTHN_CHALLENGE_TTL` per deployment. Production browser origins must use exact HTTPS
origins; HTTP is accepted only for local `localhost`/`127.0.0.1` development.

## `POST /api/v1/auth/oidc/{provider}/authorize`

Starts the browser-safe authorization-code flow for an explicitly configured OIDC provider. The
client generates an RFC 7636 verifier and its `S256` `code_challenge`, then submits both in a JSON
or `application/x-www-form-urlencoded` request body over TLS. The service validates the pair,
creates a random state, nonce, and browser transaction, stores the callback material including the
verifier and only the transaction's SHA-256 hash in Redis for five minutes, and responds with
`302 Found` to the provider's configured authorization URI. It also sets a per-state transaction
cookie with `HttpOnly`, `Secure`, `SameSite=Lax`, and the `/api/v1/auth/oidc` path attributes. The
provider can therefore redirect directly to the identity-service callback without placing the
verifier in a URL, while a copied code/state pair cannot complete from another browser.

Example form request:

```text
POST /api/v1/auth/oidc/example/authorize
Content-Type: application/x-www-form-urlencoded

code_challenge=<S256-challenge>&code_challenge_method=S256&code_verifier=<verifier>
```

Provider names, issuer endpoints, client credentials, and callback URIs are allow-listed in
deployment configuration under `identity.auth.oidc.providers`; request input cannot select an
arbitrary issuer or redirect URI. The legacy `GET` authorization endpoint remains available for
private clients that retain the verifier and forward it as `X-PKCE-Code-Verifier` on the callback.
When deployed behind a load balancer, configure `identity.auth.trusted-proxy-addresses` with the
exact immediate proxy addresses. Forwarded client addresses are ignored from all other peers when
computing keyed audit fingerprints.

## `GET /api/v1/auth/oidc/{provider}/callback`

Consumes callback state atomically and completes the provider exchange. Browser-safe authorization
starts require the matching per-state browser transaction cookie as well as the verifier retained
in the consumed Redis state. Redis compares the SHA-256 hash before deleting state, so a callback
from another browser does not invalidate the initiating browser's transaction. Private clients
using the legacy GET start may supply the verifier with `X-PKCE-Code-Verifier`; the callback does
not accept a `code_verifier` query parameter because query strings are commonly logged. The callback
rejects missing, expired, reused, provider-mismatched, transaction-mismatched, or PKCE-mismatched
state before contacting the provider. The ID token must have a valid signature and time window from
the configured JWKS, the exact configured issuer, the configured client ID in `aud`, a matching
nonce, and `email_verified=true`.

| Status | Condition | Body |
| --- | --- | --- |
| `200 OK` | Valid callback and active linked/new account | Shared `LoginResponse` session/token result |
| `400 Bad Request` | Invalid authorization-start request | Generic RFC 9457 problem detail |
| `401 Unauthorized` | Invalid, expired, reused, transaction-mismatched, or otherwise mismatched callback; unverified email; provider subject/email conflict | Generic OIDC failure; provider tokens and claim values are not returned |
| `409 Conflict` | Active-session capacity reached | Generic session-capacity problem detail |
| `503 Service Unavailable` | Redis callback-state, provider exchange, audit, database, or session dependency failed | Generic temporary-failure problem detail |

Provider-subject mappings are stored only after the ID token is validated. A new verified provider
email creates a new LifeOS account. If the email already belongs to an unlinked LifeOS account, the
callback is rejected rather than implicitly linking or taking over the account; explicit linking
requires the later authenticated step-up/account-management flow. Provider access and refresh
tokens are never returned to downstream LifeOS services or persisted.

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
| `external_identity` | Verified provider/subject to LifeOS-account mappings; no provider tokens. |
| `security_audit_event` | Redacted authentication outcomes and correlation metadata. |

See [`UserAccount`](../../services/identity-service/src/main/java/com/lifeos/identity/account/UserAccount.java), [`PasswordCredential`](../../services/identity-service/src/main/java/com/lifeos/identity/auth/PasswordCredential.java), and [`AuthSession`](../../services/identity-service/src/main/java/com/lifeos/identity/auth/AuthSession.java). Schema is currently managed by Hibernate's `ddl-auto: update` — this is a known Phase 1 shortcut; a real migration tool (e.g. Flyway) should replace it before this goes further, per the persistence-model tradeoff called out in [ADR-008](../adr/ADR-008-use-postgresql-as-system-of-record.md).

For deployed environments, set `IDENTITY_DATASOURCE_URL`, `IDENTITY_DATASOURCE_USERNAME`, and `IDENTITY_DATASOURCE_PASSWORD` rather than relying on the local-development defaults in `application.yml`.
