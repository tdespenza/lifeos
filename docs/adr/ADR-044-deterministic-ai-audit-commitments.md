# ADR-044: Deterministic commitments for redacted AI audit events

## Status

Accepted — foundation and opt-in relay implemented; external anchoring remains pending.

## Context

The AI Assistant already persists bounded audit metadata and keyed fingerprints, but an audit row
did not yet have a stable commitment that could be handed to an outbox or Trust Ledger without
reconstructing private request content. FR59 requires durable auditability and FR67 requires an
eventual hash-only blockchain boundary.

## Decision

Each newly written `assistant_request_audit_event` stores a lowercase SHA-256 commitment over a
length-prefixed canonical sequence of the bounded redacted fields, including the owner/conversation
identifiers, classifications, counters, safe summaries, keyed input/output/client fingerprints,
correlation id, and tool/provider metadata. Raw prompt, completion, bearer, address, and document
content are never part of the canonical sequence. The database id and write timestamp are excluded
so the commitment is reproducible from the redacted event itself. Rows created before migration V3
may remain null; new rows must contain exactly 64 lowercase hexadecimal characters.

The same transaction creates one `ai_audit_hash_outbox_event` envelope keyed by the immutable audit
event id. The outbox contains only the commitment and minimal identifiers. When
`AI_ASSISTANT_AUDIT_OUTBOX_RELAY_ENABLED=true`, a bounded leased relay publishes the hash-only
CloudEvent to Kafka with an idempotent producer, capped retries, and a durable local dead-letter
row. The relay is disabled by default and does not claim a blockchain transaction.

## Consequences

- Audit integrity can be checked or exported without retaining private AI content.
- A durable outbox prevents a successful audit write from being silently detached from its future
  anchoring workflow.
- Canonical length-prefixing prevents delimiter ambiguity and preserves null-vs-empty semantics.
- Existing rows are migrated without fabricating historical hashes.
- Kafka ACLs/topic retention and a reviewed Trust Ledger/Besu anchor worker are still required for
  FR67; the relay only provides at-least-once broker delivery.

## Verification

`AssistantResponseAuditIntegrationTest` asserts that a successful response persists a 64-character
commitment while the provider output remains absent from the durable entity representation. Relay
configuration is opt-in and bounded by lease, retry, batch, concurrency, and publish-timeout
properties under `ai-assistant.audit-outbox`.
