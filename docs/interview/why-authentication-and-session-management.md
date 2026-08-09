# Why the Identity Service Owns Authentication and Sessions

LifeOS needs more than a login form. It needs first-party login, OAuth2/OIDC,
passkeys, JWT validation, authorization, device management, and revocation to
behave as one security model across Angular, JavaFX, Flutter, the gateway, and
every protected service. The identity service is therefore the authority for
identity and session lifecycle; clients never issue identities and domain
services never implement their own password or provider flows.

I chose a broker-and-session-authority model instead of delegating everything to
one external identity provider because this project is intentionally a deep
identity/platform engineering exercise. The boundary still allows a managed
provider later: the identity service can become an account-linking and
authorization facade while preserving the downstream token/session contract.

The first-party path uses email plus password with Argon2id. Passwords are never
stored in plaintext or reversible form, and authentication failures use a
generic response so an attacker cannot enumerate accounts. OAuth2/OIDC uses
authorization code plus PKCE, with issuer/audience/state/nonce validation. The
passkey path uses WebAuthn challenges, origin and RP-ID validation, single-use
TTL state, and stored public keys only. These are separate protocol flows, but
they converge on one LifeOS session authority.

Access tokens are short-lived signed JWTs containing the minimum subject,
session, issuer, audience, expiry, and authorization claims. Refresh tokens are
opaque, high-entropy, one-time values; only their hashes are stored, and
rotation/reuse detection revokes the session family. Durable session metadata
lives in the identity PostgreSQL database so a user can view and revoke
devices. Redis handles high-churn TTL state such as rate limits, WebAuthn/OIDC
challenges, and hot-path revocation/cache lookups, but Redis is not the durable
source of session history.

That split is deliberate. Keeping all session state in Redis would make a Redis
restart silently erase durable device history; keeping every rate-limit counter
and challenge in PostgreSQL would add unnecessary write pressure to the
identity system of record. The tradeoff is two dependencies and a need for
explicit timeouts, idempotency, metrics, and graceful degradation.

Authorization is layered: the gateway performs coarse authentication and route
checks, while each domain service remains responsible for object-level
authorization. RBAC and ABAC decisions are represented by minimum claims and
resource/tenant checks rather than trusting a client-provided role. Service to
service calls use authenticated workload identity and mTLS where the deployment
environment requires it.

The main risks are refresh-token races, account enumeration, provider callback
confusion, WebAuthn replay, key rotation mistakes, and logging sensitive data.
The acceptance criteria in [Epic 1](../epics.md#epic-1-account-identity--access)
and [ADR-020](../adr/ADR-020-use-identity-service-for-multi-mode-authentication-and-session-management.md)
make those risks testable: concurrent refresh must have one winner, invalid
state/nonce/challenges must fail closed, revoked sessions must remain revoked
after Redis restarts, and logs/audits must contain no passwords, tokens, cookies,
private keys, or raw authenticator secrets.

Relevant ADRs: [ADR-008](../adr/ADR-008-use-postgresql-as-system-of-record.md),
[ADR-010](../adr/ADR-010-use-redis-for-cache-and-rate-limits.md), and
[ADR-020](../adr/ADR-020-use-identity-service-for-multi-mode-authentication-and-session-management.md).
