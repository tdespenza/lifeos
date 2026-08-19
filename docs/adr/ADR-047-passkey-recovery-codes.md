# ADR-047: One-Time Passkey Recovery Codes

## Status

Accepted for the Identity service foundation.

## Context

Passkey enrollment and assertion login are implemented, but a user who loses every authenticator
needs a bounded recovery policy. Recovery must not persist raw codes, expose account existence, or
bypass normal session capacity and audit controls.

## Decision

Identity exposes two explicit boundaries:

- An authenticated caller generates a replacement set of bounded `ABCD-EFGH-JKLM`-style codes.
- An unauthenticated caller exchanges one code plus an email address for one `PASSKEY` session.

Codes are HMAC-SHA-256 digests under a deployment-managed secret, have a bounded expiry (15 minutes
by default), are single-use under a pessimistic row lock, and are invalidated when a replacement set
is generated. Recovery is rate-limited using the existing login limiter, creates sessions through
the shared JWT/session authority, and writes redacted audit outcomes. No raw code, email, account
lookup result, or provider detail is logged.

The browser must display or store the generated codes securely; the service does not email or push
them. Production deployments must provide `IDENTITY_PASSKEY_RECOVERY_SECRET` through a secret
manager. Recovery communications and UI remain outside this server-side policy.

## Consequences

The policy provides a deterministic, testable recovery path without weakening WebAuthn verification
or allowing the last available sign-in method to be silently removed. It adds one durable table and
requires clients to handle one-time display and secure storage correctly.
