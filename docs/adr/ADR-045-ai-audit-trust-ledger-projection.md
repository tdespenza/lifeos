# ADR-045: Hash-only AI audit projection into Trust Ledger

## Status

Accepted — durable projection and opt-in broker path implemented; external anchoring remains pending.

## Context

AI Assistant audit rows now have deterministic commitments and a transactional outbox, but Trust
Ledger had no bounded consumer contract for those commitments. Reusing the document-proof schema
would mix domains and could accidentally imply that AI prompts or document metadata are ledger
inputs.

## Decision

Define `com.lifeos.ai.audit.hash.requested.v1` on topic
`lifeos.ai.audit.hash.requested.v1`. The CloudEvent data contains only the immutable audit event
UUID, optional owner/conversation UUIDs, and the 64-character commitment. Trust Ledger's opt-in
consumer validates source, type, subject, event/data identity, and commitment format, then inserts
one `trust_ai_audit_hash_request` row keyed by the audit event UUID with state
`PENDING_EXTERNAL_ANCHOR`. Exact redelivery is a no-op; a conflicting commitment for an existing
event ID is rejected and routed to the Kafka DLT by the existing bounded error handler.

The consumer is disabled by default and uses a distinct topic/group from Document Vault proof
commands. AI Assistant's corresponding outbox relay is also disabled by default; when explicitly
enabled it uses bounded leases/retries and a durable dead-letter row. The Trust Ledger consumer
records no prompt, completion, token, provider, or document content and does not call Besu/Web3j.
A later reviewed worker may anchor only the commitment and minimal metadata.

## Consequences

- AI audit commitments now have a durable, independently scoped Trust Ledger projection.
- Existing document-proof authorization and state transitions are unaffected.
- Kafka ACL/topic provisioning and a real external anchor worker remain deployment work.

## Verification

`TrustAiAuditHashIngressIntegrationTest` verifies first-write persistence, exact replay deduplication,
untrusted-source rejection, and conflicting-commitment rejection against H2 migrations.
