# Merkle Document Proofs

`contracts:trust-ledger` provides the pure, bounded cryptographic core for the future Besu-backed
Trust Ledger service. It never persists a document, title, path, account identifier, or private
metadata.

## Canonical document hash

`DocumentHasher` streams at most 100 MiB through Java's standard SHA-256 implementation using a
fixed 16 KiB buffer. The digest binds a domain tag, length-prefixed canonical non-private metadata
(`mediaType`, `proofPurpose`), and content. Metadata tokens are normalized to lower case; empty,
oversized, or unreadable content produces no proof result.

## Merkle format

For an ordered, unique batch of at most 10,000 document digests:

- leaf = `SHA-256(0x00 || documentDigest)`;
- internal node = `SHA-256(0x01 || left || right)`;
- an odd final node is duplicated as its own right sibling.

The domain bytes prevent treating an internal hash as a document leaf. `MerkleTree` creates bounded
inclusion proofs and `MerkleProofVerifier` deterministically returns false for altered leaves,
siblings, paths, or roots. Build time is O(N), storage is O(N), and proof verification is O(log N).

This is not yet an on-chain anchor: Besu/Web3j submission, durable outbox, idempotency, and ledger
status are service-layer work still required for FR65–FR68. The library makes FR63/FR64's hashing
and proof invariants independently testable now.
