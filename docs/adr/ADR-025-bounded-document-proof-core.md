# ADR-025: Keep document proof generation stateless until ledger anchoring has durable workflow state

## Status

Accepted — 2026-08-17

## Context

FR63 and FR64 need deterministic document digests and Merkle inclusion proofs without exposing
private content. FR65–FR68 additionally require an idempotent Besu transaction, confirmation
tracking, retry/outbox behavior, revocation/ledger interpretation, and evidence that success is not
reported before a transaction confirms. Treating those workflows as a synchronous extension of a
hash endpoint would either block a user request on a chain node or create an untracked duplicate
submission risk.

The initial product needs a safe shared proof format now: Document Vault can later submit only an
immutable digest/reference to a durable anchor workflow, and verifiers can check a supplied proof
without downloading a document or calling a ledger for simple cryptographic validation.

## Decision

`contracts:trust-ledger` owns pure Java 25 primitives and `trust-ledger-service` exposes only
three authenticated, bounded, stateless operations initially:

1. Stream a non-empty document into a domain-separated SHA-256 proof with canonical non-private metadata.
2. Build a deterministic ordered Merkle tree and return inclusion paths.
3. Verify a supplied bounded path locally.

The service never persists document bytes, creates a public document/account lookup route, or
labels a result as anchored. Proof computation needs no server-side idempotency record because it
has no mutation; future document anchoring and AI-audit anchoring are distinct durable commands with
required idempotency, a PostgreSQL outbox, bounded full-jitter retry, and explicit
`pending`/`confirmed`/`failed` status. Goal certificates use a separate Task/Goal completion
projection and Trust Ledger certificate table with the same fail-closed pending/confirmed rule.

The proof format is fixed as:

- document: `SHA-256("lifeos:document-proof:v1\\0" || metadataLength || canonicalMetadata || bytes)`;
- Merkle leaf: `SHA-256(0x00 || documentDigest)`;
- Merkle internal node: `SHA-256(0x01 || left || right)`;
- odd tree level: duplicate the final node as the right sibling.

Only `mediaType` and `proofPurpose` are canonical metadata fields. Names, descriptions, owner or
tenant IDs, paths, tags, source URLs, and arbitrary metadata are prohibited from the hash context
and on-chain payload design.

## Consequences

- The hash and proof API is immediately deterministic, bounded, inexpensive, and independently
  verifiable; a ledger outage cannot make a local proof look valid.
- Callers must not treat a Merkle root as blockchain-anchored. The API and response algorithm make
  no such claim.
- A future Besu/Web3j adapter must be introduced behind an explicit transaction/outbox boundary,
  with minimal non-sensitive root metadata and chain confirmation evidence. It cannot silently
  replace the stateless endpoint.
- Document Vault integration must retain document/version ownership locally and publish only the
  immutable digest/reference needed by that future command.

## Verification

The library has unit coverage for deterministic streaming, oversize/empty/unreadable rejection,
odd tree levels, tampering, duplicate leaves, and bounded paths. The service has HTTP contract and
Spring integration tests for authorization, multipart hashing, Merkle generation, verification,
and inbound upload timeouts.
