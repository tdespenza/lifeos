# ADR-020: Use the identity service as the authentication and session authority

## Context

LifeOS must support first-party login, OAuth2/OIDC login, passkeys/WebAuthn,
JWT-based authenticated sessions, RBAC/ABAC authorization, and device/session
revocation (FR7–FR12). The current `identity-service` only registers accounts
and intentionally stores no credentials. Implementing one login path without a
shared session and identity model would create incompatible tokens, duplicate
security policy in clients, and make later OAuth or passkey support expensive to
retrofit.

Authentication is a security boundary. The design therefore needs explicit
ownership, token lifetimes, credential storage rules, revocation behavior,
browser/mobile handling, and failure semantics before implementation starts.

## Options Considered

- **Delegate all authentication to an external identity provider:** reduces
  password and passkey implementation risk, but makes the platform's identity
  and session behavior provider-specific and weakens the project's ability to
  demonstrate identity-service engineering. It also does not remove the need
  for local account linking, authorization, device management, or audit data.
- **Implement separate password, OIDC, and passkey flows in each client:**
  rejected because clients would own security-sensitive protocol logic, token
  handling would diverge, and every service would need to trust multiple token
  formats.
- **Use the identity service as a broker and session authority (chosen):** the
  identity service owns account linking, credential and WebAuthn metadata,
  OIDC callbacks, token issuance, refresh-token rotation, device sessions, and
  authorization claims. Clients use protocol-appropriate flows but never issue
  or validate their own identities.

## Decision Made

The `identity-service` is the system of record and policy authority for user
authentication and sessions.

### First-party login

FR7 uses email plus password as the initial first-party login method. Passwords
are hashed with Argon2id using a maintained library and an explicit work-factor
configuration; plaintext passwords, reversible encryption, and password hashes
in logs are prohibited. Login failures return a generic response so account
existence is not disclosed. Login and credential-management endpoints are
rate-limited through Redis with bounded retry behavior and audit events for
security-relevant outcomes.

### OAuth2/OIDC

OAuth2/OIDC uses the authorization-code flow with PKCE. The identity service
owns provider callbacks, validates issuer, audience, nonce, state, and PKCE
requirements, and links a verified provider subject to an existing or newly
created LifeOS account according to an explicit account-linking policy. A link
requires a provider-reported verified email (`email_verified=true`) plus an
already authenticated LifeOS session or explicit reauthentication/step-up;
matching email alone never authorizes linking. Provider-subject or email
conflicts are rejected with a generic response and routed through account
recovery or support, never automatic takeover. Unlinking requires recent
reauthentication and at least one remaining usable recovery/authentication
method; recovery itself must verify an existing LifeOS factor or verified
account-recovery channel and must not trust an unverified provider claim.
Access tokens from providers are never exposed to downstream LifeOS services.

### Passkeys

WebAuthn registration and authentication use challenge records with short TTLs,
single use, origin/RP-ID validation, user-verification requirements, and
credential-counter checks. Private keys remain in the authenticator; the
identity service stores only the WebAuthn credential public key and required
metadata.

### Tokens and sessions

- The identity service issues short-lived, signed JWT access tokens containing
  the user id, session id, issuer, audience, expiry, and authorization claims.
- Refresh tokens are opaque, high-entropy, one-time values. Raw refresh tokens
  are never stored durably or in plaintext; the durable token-family record
  contains a family identifier, the active token identifier/hash, and bounded
  consumed or revoked token identifiers/hashes. A successful refresh atomically
  validates the active identifier, records it as consumed, and advances the
  active token to a newly issued successor. To tolerate an ambiguous network
  outcome, exactly one retry is accepted for 30 seconds when it presents the
  same client idempotency key and request fingerprint; a bounded TTL cache must
  retain the KMS-encrypted successor response envelope for that retry and
  returns it idempotently. A consumed predecessor with a different key or
  fingerprint, a second retry, or a retry after 30 seconds atomically revokes
  the entire token family. Row locking or an equivalent conditional update
  ensures concurrent requests cannot mint two valid successors.
- Durable session and revocation metadata is stored in the identity PostgreSQL
  database so users can view and revoke devices; PostgreSQL is the durable
  revocation authority. Redis stores bounded TTL state for rate limits, login
  challenges, and hot-path revocation/cache lookups. A Redis hit may accelerate
  a revocation-sensitive decision, but a miss, timeout, or outage falls back to
  PostgreSQL; if both stores are unavailable, the request is rejected
  fail-closed. Revocation is committed to PostgreSQL before cache invalidation,
  and recovery repopulates Redis from PostgreSQL without promoting a revoked
  session from stale cache state.
- Browser clients use refresh cookies with `Secure`, `HttpOnly`, `Path=/api/v1/auth`,
  no `Domain` attribute (host-only), and `SameSite=Lax`. Cookie-authenticated
  refresh, logout, session-revocation, and account-linking requests require a
  CSRF token and an `Origin` value matching a configured LifeOS web origin;
  missing or mismatched validation is rejected. Mobile and desktop clients use
  their platform secure storage. Access tokens are sent as bearer tokens only
  over TLS.
- Key material is loaded from a secrets manager, rotated with overlapping key
  windows, and exposed through a JWKS endpoint for verifiers. Private signing
  keys are never committed or logged.

### Authorization and service boundaries

The identity service evaluates RBAC and ABAC policy for user-facing actions and
places the minimum required claims in access tokens. The API gateway performs
coarse authentication and route checks; each service remains responsible for
object-level authorization using the authenticated subject and tenant/user
scope. Service-to-service calls use authenticated workload identity and mTLS
where the deployment environment requires it.

## Consequences

- All clients and services share one versioned authentication contract and one
  session lifecycle.
- The identity database gains credential, external-identity, WebAuthn,
  session, signing-key metadata, and security-audit records incrementally as
  the corresponding stories need them; no story creates unrelated tables.
- Redis becomes part of the login hot path, so explicit timeouts, bounded
  retries, graceful degradation, and metrics are required.
- Every protected-data request performs a session/revocation check against
  PostgreSQL as the durable session/revocation authority. Local JWT validation
  is only an early signature/claims filter, and Redis is only an acceleration
  layer; neither can replace the durable check. Redis misses or outages fall
  back to PostgreSQL, both stores unavailable fail closed, and recovered cache
  state is repopulated from PostgreSQL. No cache-only acceptance window is
  allowed for protected-data access.
- OIDC provider and WebAuthn integration tests require contract fixtures and
  security-focused negative cases; real provider secrets are never used in CI.

## Security and Reliability Invariants

- No endpoint reveals whether an email is registered through error text,
  timing-sensitive branching, or recovery behavior.
- Every authentication attempt has a bounded timeout and a correlation id;
  secrets, tokens, cookies, credentials, and authenticator data are redacted
  from logs and traces.
- Refresh-token rotation is atomic and idempotency-safe under concurrent use;
  a replay cannot mint two valid successor sessions.
- Logout/revocation is monotonic: a revoked session cannot become active again
  because Redis was unavailable or restarted.
- Login, provider-link, passkey-registration, refresh-reuse, authorization,
  and revocation decisions are audit logged without storing sensitive token
  material.

## When This Decision Would Be Wrong

If LifeOS becomes a production consumer product with compliance obligations or
an operating team too small to maintain credential, OIDC, and WebAuthn
security, a managed identity provider should become the primary protocol
authority. The identity-service boundary can remain as an account-linking and
authorization facade, preserving downstream contracts while moving protocol
implementation to the provider.

## How We Will Validate It

Validate each story with unit, integration, contract, security, and end-to-end
tests. The authentication reference flow must demonstrate: a successful
first-party login; rejected invalid credentials without account enumeration; a
successful OIDC callback with invalid state/nonce rejection; a successful
WebAuthn assertion with replay rejection; refresh rotation under concurrent
requests; device listing and revocation; gateway rejection of missing/expired
tokens; and authorization denial for both missing roles and failed attributes.
