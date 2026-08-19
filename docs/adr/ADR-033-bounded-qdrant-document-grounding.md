# ADR-033: Bounded Qdrant projection and owner-filtered document grounding

## Status

Accepted as an opt-in foundation; production provider and embedding approval remain required.

## Context

FR41 and FR54 need document-grounded answers without copying document bytes into the assistant
database or using an unbounded PostgreSQL scan. The repository already extracts a bounded searchable
text projection in Document Vault, while the AI service deliberately does not retain prompts or
outputs. Qdrant is the dedicated vector boundary selected by ADR-011, but no deployment or model
credentials can be assumed in source control.

## Decision

Document Vault optionally projects at most 64 chunks of at most 1,600 characters after a committed
upload. Each point contains only an opaque document/chunk UUID, owner/tenant scope, immutable
document version, and bounded snippet. The relational document row remains authoritative and a
Qdrant outage never rolls back a committed upload; a matching idempotent retry retries projection.

AI Assistant exposes `POST /api/v1/assistant/grounded-questions`. Every request requires active
Profile AI personalization consent with the `DOCUMENTS` category. Every Qdrant search is owner
filtered, has a hard result limit and two-second timeout, and returns source document UUIDs. Empty
evidence produces an explicit insufficient-evidence response. Qdrant/provider unavailability
returns a structured retryable `503` and never falls back to a full database scan or fabricates
claims. The default embedding is deterministic and bounded for local contract testing only; a
reviewed model embedding adapter is required for production semantic quality.

Document summaries additionally require active Profile AI personalization consent with the
`DOCUMENTS` context category. Retrieval is pinned to the highest indexed version for the requested
document, rejects invalid negative versions, and returns the bounded chunk UUIDs used by the
provider. A local deterministic provider may produce an extractive, clearly labeled summary when
explicitly enabled; provider-backed generation and production embedding quality remain deployment
responsibilities.

## Consequences

This creates an independently observable, eventually consistent vector projection and a safe RAG
contract that can be exercised with a local Qdrant deployment. It does not claim durable outbox
reconciliation, model quality, managed consent UX, MongoDB conversation memory, or a hosted provider;
those remain explicit follow-up work before marking FR41/FR54 complete.
