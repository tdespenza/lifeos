# ADR-050: Digest-only Media session-summary anchoring

## Status

Accepted — the repository implementation, provider boundary, and focused tests are present.
Production chain deployment and consent UX remain external prerequisites.

## Context

Media produces an immutable, owner-scoped post-session artifact containing a deterministic summary
and bounded action-item list. A user may want an integrity proof without putting transcript text,
summary text, action items, account identifiers, or storage paths on a ledger. Trust Ledger already
owns durable idempotency and the external Web3j/Besu boundary used for receipt-confirmed anchors, so
Media must not call a chain directly or duplicate that state machine.

## Decision

Media exposes `POST /api/v1/media/sessions/{sessionId}/post-session/anchor`. The command requires
the owner's bearer credential, a strong `If-Match` for the immutable artifact version, and a durable
`Idempotency-Key`. Media rechecks owner and tenant scope, authorizes `media:session-update`, and
computes a domain-separated SHA-256 digest over the artifact ID, version, deterministic summary, and
action-item JSON. Only the digest, artifact identity/version, and validated Identity subject proof
cross the workload-authenticated Media-to-Trust-Ledger boundary.

Trust Ledger authorizes the exact V2 capability `trust:session-summary-anchor`, persists a scoped
idempotency record, and calls the configured digest-only anchor adapter outside the database claim.
The response is `CONFIRMED` only after a non-empty external receipt; unavailable providers return a
retryable `503`, while matching retries replay the same durable result and changed key input returns
`409`. The default adapter remains fail-closed until a reviewed Besu/Web3j deployment is configured.

## Security and privacy

The digest is domain-separated (`media-session-summary-v1`) and contains no private transcript or
summary content outside the hash input. Raw bearer tokens are never forwarded. Trust Ledger derives
authorization facts from the validated subject proof and binds every request to owner and tenant.
Audit records contain only redacted operation/outcome facts.

## Tradeoffs and limits

This proves integrity of the exact local artifact version; it does not prove transcription quality,
authorship, or external processing. A chain receipt and key/network controls are still required for
production verification. The synchronous boundary is bounded by a semaphore, transaction timeout,
and configured client deadline; reconciliation retries by the same deterministic key after provider
failure.

## Verification

`TrustDigestAnchorServiceTest` covers confirmation/replay, changed-digest conflict, provider
failure recovery, and convergence when a concurrent request wins the unique reservation race.
`MediaControllerContractTest` covers the owner-scoped endpoint, strong ETag, location, and returned
anchor state. The `lifeos.trust.anchor.operation` metric records only bounded operation/outcome
labels for confirmation latency. Module migrations include PostgreSQL and H2 equivalents.
