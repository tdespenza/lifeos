# identity-service API

Base URL (local): `http://localhost:8081`

Management URL (local): `http://localhost:9081`

Status: account registration, first-party email/password login, configured OAuth2/OIDC authorization-code login, passkey/WebAuthn assertion login, short-lived JWT issuance, JWKS publication, durable JWT/session validation, one-time refresh-token rotation, deterministic RBAC/ABAC authorization decisions, and user-facing session listing/revocation are implemented. The session design is documented in [ADR-020](../adr/ADR-020-use-identity-service-for-multi-mode-authentication-and-session-management.md) and [the session diagrams](../diagrams/identity-sessions.md); `UserAccount` deliberately does not store credentials, which are owned by separate authentication-boundary entities.

The story-level first-party login diagrams are in
[`docs/diagrams/identity-login.md`](../diagrams/identity-login.md), and the OAuth2/OIDC use-case,
sequence, domain, and lifecycle diagrams are in
[`docs/diagrams/identity-oidc.md`](../diagrams/identity-oidc.md).
The implemented Story 1.4 passkey/WebAuthn use-case, sequence, domain, and lifecycle diagrams are in
[`docs/diagrams/identity-passkey.md`](../diagrams/identity-passkey.md).
Story 1.5 JWT issuance, refresh rotation, JWKS verification, and token-family lifecycle diagrams are in
[`docs/diagrams/identity-jwt.md`](../diagrams/identity-jwt.md).
Story 1.6 authorization policy, decision contract, invariants, and deployment trade-offs are in
[`docs/diagrams/identity-authorization.md`](../diagrams/identity-authorization.md).

All requests receive a validated `X-Correlation-ID` response header. A canonical UUID supplied by the trusted gateway is preserved so one request context remains correlated across the public edge and identity service; absent, repeated, or malformed values are replaced with a server-generated UUID. Registration logs include the correlation context and event outcome without logging the email address, account identifier, or database exception details.

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
| `200 OK` | Active account and active password credential verified | `LoginResponse` containing `sessionId`, signed `accessToken`, `tokenType: Bearer`, `expiresIn`, one-time `refreshToken`, and `refreshExpiresIn` seconds |
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
  "expiresIn": 300,
  "refreshToken": "<one-time opaque value, redacted>",
  "refreshExpiresIn": 2592000
}
```

The shared session/token authority issues the access token and opaque refresh token for all supported
authentication methods. Production deployments configure an RSA private/public key pair through
`IDENTITY_JWT_PRIVATE_KEY_PEM`, `IDENTITY_JWT_PUBLIC_KEY_PEM`, and `IDENTITY_JWT_SIGNING_KEY_ID`.
The prior `IDENTITY_JWT_SIGNING_SECRET` HMAC path remains for local/test compatibility only and is
never published through JWKS. Set `IDENTITY_REFRESH_REPLAY_ENCRYPTION_SECRET` independently in
production so the one-retry response envelope has a dedicated encryption key.

## `POST /api/v1/auth/refresh`

Rotates a one-time opaque refresh token. The request must include an `Idempotency-Key` header. The
service derives retry binding from the trusted resolved client address and the received `User-Agent`
header, which is caller-controlled and is only an untrusted binding signal. The value is hashed before
persistence, and the service does not accept a caller-supplied fingerprint. Browser clients may send
the token in the `lifeos_refresh` host-only cookie, while mobile and desktop clients send the token in
the JSON body. A successful rotation returns the shared `LoginResponse` with a new access JWT and
successor refresh token.

JSON clients send the exact `refreshToken` property:

```json
{
  "refreshToken": "<one-time opaque value, redacted>"
}
```

When both sources are present, the non-blank JSON `refreshToken` takes precedence; a blank or
missing body value falls back to the `lifeos_refresh` cookie. Browser responses set that host-only,
`HttpOnly`, `Secure`, `SameSite=Lax` cookie with `Path=/api/v1/auth/refresh`. Mobile and desktop
clients should use the JSON body and platform secure storage. Retry evidence is derived from the
resolved client address and received `User-Agent` header; the address is the trusted server-observed
signal, while the header remains caller-controlled and untrusted.

The token family row is locked for the transaction. The predecessor digest is moved to durable
consumed-token evidence and the successor digest is stored atomically. At most one successor is
created; one matching retry with the same idempotency key, predecessor token, and server-derived binding
returns the encrypted committed response once. A mismatched, second, or late retry revokes the
family and returns the same generic authentication failure.

| Status | Condition |
| --- | --- |
| `200 OK` | Valid active predecessor or one permitted matching retry |
| `401 Unauthorized` | Missing, expired, malformed, revoked, mismatched, or replayed token |
| `503 Service Unavailable` | Required persistence, signing, or replay-envelope dependency failed |

Login attempts are limited to five attempts per 60-second Redis window by default. Limiter keys are
HMAC-SHA-256 digests of normalized email plus the request source address, using the dedicated
`IDENTITY_RATE_LIMIT_KEY_SECRET`; raw values never enter Redis, logs, metrics, or audit events. Audit
client fingerprints use a separate `IDENTITY_AUDIT_CLIENT_FINGERPRINT_SECRET`. Both secrets must be
supplied by a secret manager and must not reuse the JWT signing key. The account's active-session
limit is ten by default.

## `GET /api/v1/auth/sessions`

Lists the authenticated account's unexpired sessions. The endpoint validates the bearer JWT and
then queries PostgreSQL with the authenticated account id; it never trusts a client-supplied account
or session owner. Results are cursor-paginated with a default page size of 20 and a maximum of 100.
The response contains only safe device classifications, authentication method, created/last-used/
expiry timestamps, `current`, and `revoked` state. It excludes token material, raw user agents,
addresses, and cookies.

```json
{
  "sessions": [
    {
      "sessionId": "7a4cf000-0000-4000-8000-000000000002",
      "deviceLabel": "chrome on macos",
      "platform": "macos",
      "browserFamily": "chrome",
      "coarseLocation": "unknown",
      "authenticationMethod": "PASSWORD",
      "createdAt": "2026-08-13T17:00:00Z",
      "lastUsedAt": "2026-08-13T17:02:00Z",
      "expiresAt": "2026-08-13T17:05:00Z",
      "current": true,
      "revoked": false
    }
  ],
  "nextCursor": "<opaque cursor or null>"
}
```

## `POST /api/v1/auth/sessions/{sessionId}/revoke`

Revokes one session owned by the caller and returns `204 No Content`. The operation locks the
account and target row with bounded database timeouts, sets durable `revoked=true`, records one
redacted audit outcome in the same transaction, and publishes a Redis revocation marker only after
the commit. Missing, foreign, already-revoked, and repeated targets return the same `204` result;
they never reveal ownership.

## `POST /api/v1/auth/sessions/revoke-others`

Revokes every active session for the caller's account except the current bearer session and returns
`204 No Content`. The account lock makes the bulk operation deterministic with concurrent session
creation and revocation. Subsequent access validation and refresh attempts for revoked sessions
fail from durable PostgreSQL state even after Redis restart; the preserved current session and
unrelated accounts remain valid.

## `GET /api/v1/auth/validate` (internal)

This is an internal migration adapter for protected services, not a browser or public-client API.
It validates the bearer JWT and then performs the durable PostgreSQL session/revocation check
required by ADR-020. A service must authenticate itself with both workload headers:

```text
Authorization: Bearer <access token>
X-LifeOS-Workload-Identity: task-goal-service
X-LifeOS-Workload-Token: <deployment-managed shared credential>
```

The workload credential is configured independently in the identity and calling service; missing,
unknown, blank, or mismatched credentials receive the same generic `401` response. The adapter
does not trust an identity header by itself, log the bearer token, or return roles/tenant data.
It has a separate, Redis-backed per-workload request budget (60,000 requests/minute by default),
not the five-attempt credential-login budget. Deploy it on an internal network boundary with
TLS/mTLS or an equivalent workload-identity control.

### Response (`200 OK`)

```json
{
  "accountId": "5e7af000-0000-4000-8000-000000000001",
  "sessionId": "7a4cf000-0000-4000-8000-000000000002",
  "authenticationMethod": "PASSWORD",
  "accessTokenProof": "<opaque internal proof, redacted>"
}
```

`accessTokenProof` is returned only to an authenticated workload so it can make the immediately
following authorization request. It binds that request to the exact bearer token that identity
validated. It is opaque contract data: it is never sent to a browser or public client and is never
written to logs, audit rows, metric labels, or public API documentation as a real value.

| Status | Condition |
| --- | --- |
| `200 OK` | Workload authenticated; bearer signature, claims, durable session ownership, revocation, expiry, and token digest are valid |
| `401 Unauthorized` | Missing/invalid workload credential or bearer token; all client-safe failures have the same generic body |
| `429 Too Many Requests` | Authenticated workload's bounded internal request budget is exceeded |
| `503 Service Unavailable` | Required identity, audit, or internal rate-limit dependency cannot complete safely |

## `POST /api/v1/internal/authorization/decisions` (internal)

Returns a deterministic authorization decision for a subject and a resource that the calling service
has already loaded. It requires `X-LifeOS-Workload-Identity` and
`X-LifeOS-Workload-Token` exactly as the validation adapter does. The current repository uses this
narrow REST adapter while the ADR-007 gRPC contracts module and mTLS rollout remain future work;
the policy domain is transport-independent.

Workload authentication and its separate Redis-backed request budget run before JSON binding. The
request body is capped at 16 KiB; unauthenticated workload failures return a generic response
without synchronously creating a durable audit row, so they cannot be used to exhaust audit storage.

The service must send an exact `expectedPolicyVersion` and only trusted owner, tenant, and
`resourceExists` attributes. It must not forward client-supplied resource facts. The JSON request
and response are unchanged between policy versions. The deployment default is `v2`; Identity serves
an immutable `v1` compatibility policy only for existing Task/Goal and Profile/Household actions and returns
`policyVersion: "v1"`; existing adapters therefore continue their exact-version check. A `v2`
action never falls back to `v1`, and no unknown version, action, workload, resource type, or
fact-shape has a permissive fallback.

Each action has one source-defined descriptor: exact workload identity, resource type, fact shape,
tenant scope, and owner rule. `TENANT_ADMIN` bypass exists only for the legacy scoped Goal object
actions; all Task, Profile, Calendar, Finance, and Trust Ledger V2 descriptors are self-only in the
subject's personal tenant. Workload credentials authenticate before request binding, then Identity
denies a verified-but-wrong workload with the bounded `WORKLOAD_NOT_AUTHORIZED` reason. The action
families below are enabled only when the active policy version is `v2` and their deployment-managed
workload credential is configured.

| Workload | Action family | Exact resource contract |
| --- | --- | --- |
| `task-goal-service` | Legacy Goal: `goal:create`, `goal:list`, `goal:read`, `goal:update`, `goal:complete`, `goal:archive`, `goal:dependency-order` | `goal:create` is an owned create; goal lifecycle actions are scoped owned objects with `ownerAccountId` and `resourceExists`; list/order are scoped `goal` collections. Legacy scoped Goal object actions retain their reviewed tenant-admin rule. |
| `profile-service` | Legacy Profile and Household actions | Profile creation/object actions use `profile` owned facts in the personal tenant. Household capability actions use a `household` UUID and exactly `requesterAccountId`; Profile service still enforces household relation and local permissions. |
| `task-goal-service` | `task:create`, `task:list`, `task:read`, `task:update`, `task:complete`, `task:cancel` | `task` owned-create/object facts, or an empty `task` collection for list; all personal/self-only. |
| `task-goal-service` | `task:dependency-manage`, `task:dependency-order` | Empty personal `task-dependency-graph` collection only. Task/Goal loads and owner-checks every graph node locally before it requests the Identity capability. |
| `calendar-service` | `calendar:event-create/list/read/update/cancel`, `calendar:time-block-create/list/read/update/cancel`, `calendar:conflict-read`, `calendar:optimize` | `calendar-event` and `calendar-time-block` owned create/object facts; empty personal `calendar` collection for list/conflict/optimization. No tenant-admin bypass. |
| `finance-service` | `finance:budget-create/list/read/update`, `finance:transaction-create/list/read/categorize`, `finance:insights-read`, `finance:forecast-read`, `finance:goal-create/list/read/update/contribute` | `finance-budget`, `finance-transaction`, and `finance-goal` owned create/object facts or their empty personal collections; insights and forecasts use the empty personal `finance` collection. No tenant-admin bypass. |
| `trust-ledger-service` | `trust:document-proof-create`, `trust:merkle-proof-create`, `trust:proof-verify`, `trust:anchor-create`, `trust:credential-verify`, `trust:ai-audit-anchor-create`, `trust:goal-certificate-create` | Empty personal `trust-ledger` collection only. The trust service owns proof/anchor persistence and validates its own domain references. |

For any owned object, the protected service supplies a UUID resource ID and exactly
`ownerAccountId` plus `resourceExists`. `resourceExists=false` receives the same bounded
`OWNER_MISMATCH` as another user's object, avoiding resource enumeration. Owned creates supply a
UUID resource ID and exactly `ownerAccountId`; collection actions require no resource ID and no
attributes. These are closed descriptor shapes, not caller-selectable policy expressions.

### Request body

```json
{
  "subjectId": "5e7af000-0000-4000-8000-000000000001",
  "sessionId": "7a4cf000-0000-4000-8000-000000000002",
  "accessTokenProof": "<opaque internal proof, redacted>",
  "action": "goal:read",
  "resource": {
    "resourceType": "goal",
    "resourceId": "f65bf000-0000-4000-8000-000000000003",
    "tenantId": "5e7af000-0000-4000-8000-000000000001",
    "attributes": {
      "ownerAccountId": "5e7af000-0000-4000-8000-000000000001",
      "resourceExists": "true"
    }
  },
  "expectedPolicyVersion": "v1"
}
```

### Decision response (`200 OK`)

```json
{
  "outcome": "DENY",
  "reasonCode": "OWNER_MISMATCH",
  "policyVersion": "v1",
  "expiresAt": "2026-08-13T18:10:00Z"
}
```

`outcome` is always `ALLOW` or `DENY`. Reason codes are bounded and intentionally omit resource
identifiers and contents. A decision is tied to the subject, session, opaque validation proof,
action, resource facts, tenant, and policy version; the returned expiry is no later than the
active session expiry. The proof is accepted only from an authenticated workload and is never
persisted in authorization audits or exposed in a public response.

| Status | Condition |
| --- | --- |
| `200 OK` | Authenticated workload; a deterministic allow or deny decision was created and audit-recorded |
| `400 Bad Request` | The internal request cannot be parsed; no input values are echoed |
| `401 Unauthorized` | Missing/invalid workload credential; configured identities and secrets are not disclosed |
| `429 Too Many Requests` | Authenticated workload's bounded internal request budget is exceeded |
| `413 Payload Too Large` | Decision request exceeds the 16 KiB bounded contract |
| `503 Service Unavailable` | Required audit persistence or internal rate-limit dependency cannot complete safely; no allow is returned |

Policy-store failures are represented as `DENY` with reason `POLICY_UNAVAILABLE`, so protected
services can return a generic `503` rather than treating an outage as a policy denial. See
[the authorization design](../diagrams/identity-authorization.md) for the full rule table,
non-enumeration behavior, audit fields, and cache constraints.

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
and signature counter. Authenticated enrollment is available through
`POST /api/v1/auth/passkey/registration/options` and
`POST /api/v1/auth/passkey/registration`; the browser bearer is validated before the ceremony,
the registration challenge is single-use Redis state, and only the verified public credential is
persisted. Recovery/replacement after losing every credential is provided by the one-time recovery
code endpoints below; the web shell now provides bounded passkey registration/login and recovery-code
display, while cross-client recovery communications remain client/deployment work.

Users can inspect and disable their own registered credentials through
`GET /api/v1/auth/passkey/credentials` and
`DELETE /api/v1/auth/passkey/credentials/{credentialId}`. The list exposes only the internal
credential row id and registration/last-use timestamps; authenticator ids, public keys, and
user handles are not returned. Removal is owner-scoped, audited, and refused with `409 Conflict`
when it would leave the account without an active password or another enabled passkey.

The list returns `200 OK` with a bounded array and `Cache-Control: no-store`; removal returns
`204 No Content`, `401 Unauthorized` for an invalid bearer, `404 Not Found` for a missing or
not-owned credential, `409 Conflict` when no alternate sign-in method would remain, and `503
Service Unavailable` for a persistence or audit dependency failure.

## `POST /api/v1/auth/passkey/registration/options`

Starts an authenticated registration ceremony for the bearer subject. The service returns an
opaque challenge handle and browser-facing `publicKey` creation options. The challenge binds the
account UUID and is consumed exactly once.

## `POST /api/v1/auth/passkey/registration`

Consumes the authenticated registration challenge, validates the browser attestation with the
configured relying party, and persists the resulting public credential. The request is accepted
only for the same account that started the ceremony; private keys and attestation blobs are never
stored.

| Status | Condition |
| --- | --- |
| `204 No Content` | Credential verified and persisted |
| `400 Bad Request` | Malformed registration envelope |
| `401 Unauthorized` | Missing/invalid bearer, stale challenge, account mismatch, or invalid attestation |
| `503 Service Unavailable` | Redis, persistence, audit, or WebAuthn dependency unavailable |

Configure `IDENTITY_WEBAUTHN_RP_ID`, `IDENTITY_WEBAUTHN_RP_NAME`,
`IDENTITY_WEBAUTHN_ALLOWED_ORIGINS`, `IDENTITY_WEBAUTHN_USER_VERIFICATION`, and
`IDENTITY_WEBAUTHN_CHALLENGE_TTL` per deployment. Production browser origins must use exact HTTPS
origins; HTTP is accepted only for local `localhost`/`127.0.0.1` development.

## `POST /api/v1/auth/passkey/recovery-codes`

Generates a replacement set of one-time recovery codes for the authenticated bearer subject. The
service invalidates unused prior codes, stores only HMAC-SHA-256 digests under
`IDENTITY_PASSKEY_RECOVERY_SECRET`, and returns the raw codes once with a bounded expiry (15
minutes by default). The caller must display or store them securely; the service never logs or
re-sends them. The same transaction enqueues a generic security notification
(`security.passkey.recovery-codes-issued`) to Identity's durable notification outbox; the event
contains no raw code or account contact data. Notification provider delivery remains deployment-owned.

```text
Authorization: Bearer <access-token>
```

```json
{
  "codes": ["ABCD-EFGH-JKLM", "MNPR-STUV-WXYZ"],
  "expiresAt": "2026-08-19T12:00:00Z"
}
```

| Status | Condition |
| --- | --- |
| `200 OK` | Replacement codes generated and persisted |
| `401 Unauthorized` | Missing, invalid, or stale bearer |
| `503 Service Unavailable` | Account, audit, or persistence dependency unavailable |

## `POST /api/v1/auth/passkey/recover`

Consumes one recovery code and creates one `PASSKEY` session through the normal JWT/session
authority. Email lookup, invalid codes, expired codes, and replayed codes share one generic `401`
response. The existing login limiter applies before account lookup, and the code is consumed under
a row lock before session creation, so a concurrent race cannot create two sessions from one code.
After a successful session commit, Identity enqueues the generic
`security.passkey.recovery-succeeded` notification through the same durable outbox boundary.

```json
{
  "email": "ada@example.com",
  "code": "ABCD-EFGH-JKLM"
}
```

| Status | Condition |
| --- | --- |
| `200 OK` | Valid unused code and active account; shared `LoginResponse` returned |
| `400 Bad Request` | Malformed email or code shape |
| `401 Unauthorized` | Unknown account, invalid/expired/replayed code |
| `429 Too Many Requests` | Recovery attempt limit exceeded |
| `409 Conflict` | The account reached its bounded active-session capacity |
| `503 Service Unavailable` | Rate limiter, audit, session-authority, or persistence dependency unavailable |

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

Register a new **first-party password account**. This is distinct from the internal OIDC account
linking path: a verified OIDC callback may create an account identity but deliberately does not
create a local password credential. An OIDC-only account cannot use `POST /api/v1/auth/login`
until a future authenticated credential-enrollment flow explicitly creates one.

The request requires exactly one `Idempotency-Key` header. It is an opaque 1–128 character value
matching `[A-Za-z0-9][A-Za-z0-9._~-]{0,127}`. LifeOS stores only a dedicated HMAC digest of the
key and an HMAC request fingerprint; neither the key, password, email, nor display name is stored
in the retry record. Reuse a key only when retrying the exact same command.

```text
POST /api/v1/accounts
Idempotency-Key: register-ada-001
Content-Type: application/json
```

### Request Body

```json
{
  "email": "ada@example.com",
  "displayName": "Ada Lovelace",
  "password": "correct horse battery staple"
}
```

`email` must be non-blank and valid, and `displayName` must be non-blank. The password must be
12–128 characters, contain at least one non-whitespace character, and contain no ISO control
characters. Password whitespace is otherwise preserved. The request creates the `user_account`,
one bounded Argon2id `password_credential`, its completed idempotency record, and a redacted
success audit outcome in one transaction. The plaintext password is never persisted or returned.

### Responses

| Status | Condition | Body |
| --- | --- | --- |
| `201 Created` | First completion of a valid command | `AccountResponse`, `Location: /api/v1/accounts/{id}`, and `Cache-Control: no-store` |
| `200 OK` | Exact replay of a completed command with the same idempotency key and payload | The original `AccountResponse`, `Location`, and `Cache-Control: no-store`; no second credential is created |
| `400 Bad Request` | Missing, duplicated, or malformed retry key; malformed fields; or password-policy failure | Generic RFC 9457 problem detail; field values, keys, and passwords are not echoed |
| `409 Conflict` | Existing email or an idempotency key reused with different payload | One generic RFC 9457 problem detail; it does not indicate which condition occurred |
| `503 Service Unavailable` | Password-hash capacity, idempotency persistence/lock, or audit persistence is unavailable | Generic problem detail plus `Retry-After: 1`; retry with the original key and unchanged payload |

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

Fetch the caller's own account by id. Supply an access token created by a current, non-revoked
LifeOS session:

```text
Authorization: Bearer <access-token>
```

The service validates the JWT and durable session state before comparing the bearer subject to
`{id}`. A request for another account is audited with the caller's account id and a redacted client
fingerprint only; the target id is not retained in the audit event. Requests for another account
and nonexistent accounts intentionally have the same `404` response, preventing account-resource
enumeration.

### Responses

| Status | Condition | Body |
| --- | --- | --- |
| `200 OK` | Valid bearer subject owns `{id}` | `AccountResponse` and `Cache-Control: no-store` |
| `401 Unauthorized` | Missing, malformed, invalid, expired, or revoked bearer token | Generic authentication problem detail |
| `404 Not Found` | `{id}` is not owned by the caller, or it does not exist | The same generic problem detail for both conditions |
| `503 Service Unavailable` | Required authorization/audit dependency is unavailable | Generic problem detail plus `Retry-After: 1` |

## Operational endpoints

Spring Boot Actuator endpoints exposed by the service:

| Endpoint | Purpose |
| --- | --- |
| `GET http://localhost:9081/actuator/health` | Overall service health; database details are not included in the response |
| `GET http://localhost:9081/actuator/health/liveness` | Process liveness probe |
| `GET http://localhost:9081/actuator/health/readiness` | Readiness probe, including database availability |
| `GET http://localhost:9081/actuator/prometheus` | Prometheus-compatible request and application metrics |

Actuator runs on a separate management listener bound to loopback by default. Set `IDENTITY_MANAGEMENT_PORT` and `IDENTITY_MANAGEMENT_ADDRESS` for a private deployment interface, and keep that listener behind the deployment network boundary. Only the endpoints above plus `info` are exposed (see [`application.yml`](../../services/identity-service/src/main/resources/application.yml)) — no `/actuator/env`, `/actuator/beans`, etc. are exposed, to avoid leaking configuration details. HTTP observations are traced through the configured Micrometer/OpenTelemetry bridge and exported to the OTLP endpoint configured by `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT`.

The public Tomcat listener applies `IDENTITY_INBOUND_REQUEST_TIMEOUT` (10 seconds by default;
validated from 1 ms through 60 seconds) to initial request reads, idle keep-alive sockets, and
request-body uploads. This explicitly enables Tomcat's upload timeout so a stalled client cannot
hold an inbound servlet request indefinitely. OIDC token exchange and JWK retrieval use the
observation-enabled Spring Boot `RestClient.Builder` and `RestTemplateBuilder`, respectively, so
their outbound calls retain the configured W3C trace propagation as well as the explicit
`IDENTITY_OIDC_PROVIDER_CONNECT_TIMEOUT` (2 seconds) and
`IDENTITY_OIDC_PROVIDER_READ_TIMEOUT` (5 seconds) limits.

## Data store

PostgreSQL, database `lifeos_identity`, with these identity-owned tables:

| Table | Purpose |
| --- | --- |
| `user_account` | Canonical account identity, status, and registration metadata; email remains protected by `uk_user_account_email`. |
| `password_credential` | One Argon2id encoded first-party credential per account; no raw password. |
| `account_registration_idempotency` | HMAC-protected public-registration retry reservation and completion state; no raw retry key or payload fields. |
| `auth_session` | Durable session metadata and SHA-256 access-token digest for revocation authority. |
| `external_identity` | Verified provider/subject to LifeOS-account mappings; no provider tokens. |
| `security_audit_event` | Redacted authentication outcomes and correlation metadata. |
| `authorization_membership` | Explicit active role grants scoped to an account and tenant; personal `MEMBER` remains implicit. |

See [`UserAccount`](../../services/identity-service/src/main/java/com/lifeos/identity/account/UserAccount.java), [`PasswordCredential`](../../services/identity-service/src/main/java/com/lifeos/identity/auth/PasswordCredential.java), and [`AuthSession`](../../services/identity-service/src/main/java/com/lifeos/identity/auth/AuthSession.java). Flyway owns schema evolution and Hibernate runs with `ddl-auto: validate`, so an unexpected production schema fails startup instead of being changed implicitly. Follow the [database migration runbook](../operations/database-migrations.md) for fresh deployments, existing Hibernate-managed databases, and rollback discipline.

`IDENTITY_DATASOURCE_URL`, `IDENTITY_DATASOURCE_USERNAME`, and
`IDENTITY_DATASOURCE_PASSWORD` are required and non-blank in every non-test environment; there are
no source-controlled fallback credentials. The stock local Compose initializer creates the
`lifeos_identity` database but no separate identity-service role, so its template derives the
identity datasource user name and password from `LIFEOS_POSTGRES_USER` and
`LIFEOS_POSTGRES_PASSWORD`. Use a different identity login only after creating that role and
granting it database access. Local development can start with the gitignored
[`infrastructure/docker-compose/.env`](../../infrastructure/docker-compose/.env) derived from its
[template](../../infrastructure/docker-compose/.env.example), while deployed environments must
obtain the equivalent values from their secret manager.
