# ADR-028: Use opaque object references, bounded local staging, and durable metadata commands for Document Vault

## Status

Accepted — 2026-08-18

## Context

FR37–FR40 require private document upload, editable metadata, secure storage references, and
search. Storing file bytes in PostgreSQL makes metadata queries, database backups, retention, and
future object-store scaling unsafe and expensive. Treating a client filename or a cloud URL as a
storage key permits traversal, provider-credential leakage, and cross-user disclosure. Upload and
metadata retry behavior must also survive transport retries and process interruptions without
duplicating a document or returning a newer representation for an older command.

The first Document Vault release must not fabricate AI summaries (FR41), Trust Ledger anchoring
(FR42), or RAG/vector search (FR54) before their consent, content-access, workflow, and durable
integration contracts exist.

## Decision

- `vault_document` stores owner/tenant scope, immutable verified content facts, and one opaque
  object reference. It has no byte/blob/content column and no public route returns the reference.
- `DocumentObjectStore` is the only boundary that accepts document bytes. The bundled
  `LOCAL_DEVELOPMENT` adapter writes generated UUID staging files beneath one configured root,
  checks a closed media allow-list/signature/size/deadline while streaming SHA-256, atomically
  promotes to a generated UUID object path, and removes failed temporary files. It never accepts a
  client filename or path.
- Production configuration must provide a separately reviewed `DocumentObjectStore` adapter.
  Selecting `PRODUCTION_ADAPTER` without one fails startup. That adapter must supply private
  object access, encryption, lifecycle reconciliation, and no permanent public URLs; it is not
  guessed from environment bucket names.
- Upload and metadata replacement use actor/tenant/operation-scoped HMACed idempotency keys and
  canonical request fingerprints. The resource mutation and protected immutable response snapshot
  commit in one database transaction. Matching authorized retries replay the original public
  response and ETag exactly. A metadata retry can repair a pending snapshot after a crash only if
  the exact expected next representation already exists.
- Object promotion is intentionally outside the PostgreSQL transaction. On deterministic rejected
  paths the local object is deleted; on an ambiguous database outcome the object is retained rather
  than risking deletion of a committed reference. A future production adapter must reconcile such
  orphan candidates safely. This is an explicit availability/storage-cost tradeoff, not a hidden
  distributed transaction claim.
- Identity V2 owns `document:create`, `document:read`, `document:update`, `document:search`, and
  `document:proof-request` under workload `document-vault-service`. The service sends only locally loaded
  owner/tenant/resource-exists facts and separately applies owner scope to prevent guessed IDs from
  becoming a disclosure path.
- Metadata and privacy-safe token search reads a configurable fixed leading owner catalog window
  (10,000 default; 20,000 hard maximum) through a matching owner/tenant/update index, then
  filters/ranks only that bounded metadata set. It reports `catalogTruncated` instead of silently
  scanning an arbitrary archive. Raw file content is not persisted or exposed; at most 64 KiB of
  `text/plain`-like content, the first 100 pages of a well-formed, unencrypted PDF, or bounded
  XML parts from DOCX/PPTX/XLSX packages is reduced to keyed token digests for content matching.
  Office packages are read from an allow-listed XML-part set with ZIP entry, XML-byte, and output
  character caps; no package is unpacked to a client-controlled path.
- Proof requests use a separate owner/tenant/key reservation and transactional outbox. A bounded
  lease-based relay publishes the immutable CloudEvent at least once with capped backoff and a
  durable dead-letter record after exhaustion. The envelope is limited to document UUID/version,
  owner/tenant scope, checksum, and a versioned event type; a future Trust Ledger worker owns
  anchoring and must never treat `REQUESTED` as proof completion.

## Consequences

The foundation can safely deliver private upload and organization now while keeping the database
small, backup-friendly, and independent of object-storage implementation. It adds a clear object
lifecycle obligation: local development cleans deterministic failed stages, while production must
operate an orphan/quarantine lifecycle before durable external storage is enabled.

Search remains bounded and deterministic. Malformed/encrypted PDFs and binary media remain
metadata-searchable, so this is not an exhaustive legal-discovery or vector search system. A future
full-document extractor or vector index must be separately authorized, tenant-filtered,
retention-aware, and bounded; it must not reuse this metadata endpoint to expose raw content.

No download, AI summary, Trust Ledger call, or RAG query is present. FR42 has a durable request
and outbox foundation, while external anchoring remains pending rather than being represented by
a placeholder success state.

## Verification

`LocalDocumentObjectStoreTest` covers generated paths, signature/size/deadline rejection, and
interrupted-stream staging cleanup. `DocumentVaultServiceIntegrationTest` and
`DocumentVaultControllerContractTest` cover exact replay, ETags, metadata conflict, bounded
search, and missing/cross-owner equivalence. `DocumentVaultPostgresIntegrationTest` runs a real
PostgreSQL Testcontainers concurrent matching-upload reservation test. Migration tests assert the
metadata/idempotency/audit tables exist without a byte column, and the authorization adapter test
asserts exact V2 request facts and fail-closed decisions.
