# ADR-032: Project proof requests before external anchoring

## Status

Accepted — 2026-08-18

## Context

Document Vault and completed Task/Goal certificates can safely publish or request privacy-minimized
proofs before a blockchain adapter is available. Treating local persistence as an implicit chain
anchor would be incorrect, while keeping proof state only in a producer makes replay, audit, and
future anchor-worker handoff ambiguous.

## Decision

When `TRUST_LEDGER_KAFKA_ENABLED=true`, Trust Ledger consumes
`lifeos.document.proof.requested.v1` with a stable consumer group and persists one
`trust_document_proof_request` row per CloudEvent/request UUID. The projection contains only owner and
tenant scope, document UUID/version, checksum, receipt time, and `PENDING_EXTERNAL_ANCHOR`; it never
stores document bytes, filenames, object-store references, or private metadata. Redelivery is an
idempotent no-op. Malformed or repeatedly failing records are retried twice with a one-second fixed
backoff and then published to the topic's `.DLT` suffix.

The projection is not an anchor. A future Besu/Web3j worker must claim rows with a separate durable
state machine, idempotency key, confirmation evidence, and outbox/retry policy before any API can
report an external anchor.

Completed-goal certificates use the same rule. Trust Ledger asks Task/Goal for an owner-scoped,
workload-authenticated projection containing only goal UUID, immutable version, and completion time.
It hashes those facts with a domain separator and stores the digest in `trust_goal_certificate`.
Only the digest is sent to the optional Besu adapter; titles, notes, and task/dependency data never
cross the boundary. A certificate remains `PENDING_EXTERNAL_ANCHOR` until a receipt is confirmed.

## Consequences

- Document proof requests survive producer-to-consumer replay and can be reconciled without exposing
  document content.
- The default remains disabled, so local deployments do not require Kafka; enabling it requires the
  topic, `.DLT`, ACLs, broker TLS/authentication, and the Trust Ledger database.
- A broker outage or poison record cannot make a proof appear anchored; it only delays or dead-letters
  the pending projection.
