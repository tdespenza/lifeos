# Document Vault service API

Direct local service URL: `http://localhost:8088`

Management URL (loopback by default): `http://127.0.0.1:9088`

`document-vault-service` is the independently deployable Document Vault foundation for FR37–FR42.
It accepts owner-scoped uploads, stores only verified metadata plus opaque object references in
PostgreSQL, supports versioned metadata, and provides bounded metadata plus privacy-safe plain-text,
DOCX, PPTX, and XLSX content-token search. The gateway exposes the protected `/api/v1/documents` route
and relays only the exact multipart upload operation as a bounded request stream; direct service
URLs remain available for local development and service-level verification.

## Security and authorization boundary

Every endpoint requires `Authorization: Bearer <LifeOS access token>`. The service validates the
bearer with Identity using the `document-vault-service` workload credential, then requests an
exact V2 decision using only service-derived facts:

| Endpoint shape | Identity action | Resource facts |
| --- | --- | --- |
| `POST /api/v1/documents` | `document:create` | New UUID, personal tenant, `{ ownerAccountId }` |
| `GET /api/v1/documents/{id}` | `document:read` | Existing local document, `{ ownerAccountId, resourceExists: true }` |
| `PUT /api/v1/documents/{id}/metadata` | `document:update` | Existing local document, `{ ownerAccountId, resourceExists: true }` |
| `GET /api/v1/documents/search` | `document:search` | Personal tenant collection, no attributes |
| `POST /api/v1/documents/{id}/proof-requests` | `document:proof-request` | Existing local document, `{ ownerAccountId, resourceExists: true }` |
| `GET /api/v1/documents/{id}/proof-requests/{requestId}` | `document:read` | Owner-scoped request status |

Before read or update authorization, a local owner/tenant predicate resolves the document. A
missing ID and an ID belonging to another account therefore both return the same generic `404`
body and never disclose an object reference, filename, title, tag, checksum, or ownership fact.
An explicit Identity policy denial is `403`; validation/decision/audit dependency failure is
fail-closed `503` with `Retry-After: 1`.

Every request has a validated or generated `X-Correlation-ID`. ECS logs, OpenTelemetry tracing,
Prometheus, and the redacted audit table exclude bearer values, raw idempotency keys, client
addresses, document IDs, titles, tags, object references, checksums, and bytes. The audit table
keeps only a correlation ID, account reference where known, a keyed client-address digest, and
bounded outcome tags.

## Storage model

`vault_document` contains only:

- owner and tenant UUID/string scope;
- an opaque `object_reference` (not returned by any endpoint);
- SHA-256 checksum, content length, and allow-listed media type;
- editable metadata and optimistic-lock version.

It has no BLOB, BYTEA, data, or content column. The local development `DocumentObjectStore`
streams to a private UUID staging file, verifies media signature/size/checksum, then atomically
renames it below a generated UUID object path. It accepts no client filename or path. Failed,
oversized, deadline-expired, malformed, and interrupted streams remove their staging object.

`LOCAL_DEVELOPMENT` is the only bundled store. Set
`DOCUMENT_VAULT_STORAGE_MODE=PRODUCTION_ADAPTER` only when deployment supplies a reviewed
external `DocumentObjectStore` bean; the service otherwise fails at startup rather than silently
using container-local storage. A production adapter must offer provider-side encryption, private
bucket/container policy, lifecycle reconciliation for crash-orphaned objects, and no permanent
public URLs. There is intentionally no download endpoint in this foundation.

## Upload: `POST /api/v1/documents`

Consumes `multipart/form-data`:

| Part/parameter | Required | Rule |
| --- | --- | --- |
| `file` | Yes | Content stream. Client filename is ignored. |
| `title` | Yes | Trimmed non-blank text, at most 255 characters. |
| `tag` | No, repeatable | At most 10 canonical tags; each is 1–32 safe Unicode letters/numbers/spaces/dots/hyphens. |
| `documentTimestamp` | No | ISO-8601 instant. |
| `source` | No | `UPLOAD` (default), `SCANNER`, or `IMPORT`. |
| `classification` | No | `PRIVATE` (default), `SENSITIVE`, or `CONFIDENTIAL`. |

Required request header: exactly one `Idempotency-Key` matching
`[A-Za-z0-9][A-Za-z0-9._~-]{0,127}`. The raw key is never persisted; its HMAC-SHA-256 digest is
scoped to authenticated actor, tenant, and operation. The request fingerprint binds verified
checksum/length/media type and canonical metadata. A matching retry returns the exact original
`201`, `Location`, body, and strong ETag with `Idempotent-Replay: true`; a different request using
the same scope/key returns `409`.

The allow-list requires both declared media type and a lightweight signature check:

- `application/pdf` (`%PDF-`);
- `text/plain`, `text/csv`, `text/markdown`, and `text/html` (no NUL byte in the inspected prefix);
- `image/png` (PNG signature);
- `image/jpeg` (JPEG SOI signature);
- `application/vnd.openxmlformats-officedocument.wordprocessingml.document` (ZIP package signature);
- `application/vnd.openxmlformats-officedocument.presentationml.presentation` (ZIP package signature);
- `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` (ZIP package signature).

For DOCX, PPTX, and XLSX, search extraction reads only an allow-listed set of XML parts from the ZIP
package (document text, headers/footers/notes, presentation slides, shared strings, worksheets, or
comments), with bounded entry count, XML bytes, and output characters. XML DTDs and external entities
are disabled. Malformed,
encrypted, suspicious, or over-budget packages remain securely stored but contribute no searchable
content. An explicitly enabled local Tesseract adapter may add bounded PNG/JPEG OCR text; it
receives only the verified local object path, uses no shell, and is capped by a process deadline
and output limit. OCR is disabled by default. Other binary formats and exhaustive full-text
indexing remain outside this foundation.

The default verified file cap is exactly 10,485,760 bytes, and the multipart/envelope cap is
exactly 11,010,048 bytes. Chunked uploads are allowed: the inbound filter counts bytes when
`Content-Length` is absent. Tomcat connection, keep-alive, and upload reads plus storage staging
use a 30-second default deadline. Unsupported type is `415`, over-limit content is `413`, and a
staging deadline is `408`; none publish a usable document record.

```json
{
  "id": "9cf35a43-b2b3-42c7-9af4-b5bcc95ccf69",
  "title": "Travel receipt",
  "tags": ["finance", "travel"],
  "documentTimestamp": "2026-08-18T12:00:00Z",
  "source": "UPLOAD",
  "classification": "PRIVATE",
  "contentType": "application/pdf",
  "contentLength": 24812,
  "checksumSha256": "...64 lowercase hex characters...",
  "version": 0,
  "createdAt": "2026-08-18T12:00:02Z",
  "updatedAt": "2026-08-18T12:00:02Z"
}
```

The response deliberately has no `objectReference`, file path, storage provider identifier, or
download URL.

## Read and metadata update

| Endpoint | Behavior |
| --- | --- |
| `GET /api/v1/documents/{id}` | Returns owner-scoped public metadata and a strong numeric ETag such as `"0"`. |
| `PUT /api/v1/documents/{id}/metadata` | Replaces `{ title, tags, documentTimestamp, source, classification }`; bytes, content type, checksum, and storage reference are immutable. |

Metadata update requires exactly one strong `If-Match` ETag and one valid `Idempotency-Key`. A
missing `If-Match` is `428`; weak, wildcard, duplicate, or malformed conditions are `400`; an old
version is `412`. The durable reservation stores the original public JSON response snapshot, so a
matching retry never rereads a later document version. Recovery handles a process interruption
after resource flush but before snapshot completion when the exact requested next representation
is already present.

## Search: `GET /api/v1/documents/search`

Parameters:

- `q` — required 2–128 character metadata term using letters, numbers, spaces, dots, commas,
  apostrophes, and hyphens;
- `page` — `0`–`1000`, default `0`;
- `size` — `1`–`50`, default `20`.

Search is owner/tenant-filtered before a response is built. It searches title and canonical tags,
plus HMAC token digests extracted from at most 64 KiB of bounded UTF-8 text (`text/plain`, CSV,
Markdown, or HTML), the first 100 pages of a well-formed, unencrypted PDF, and bounded DOCX/PPTX/XLSX
XML parts. Raw content is never stored in PostgreSQL. Malformed/encrypted
PDFs and binary media remain metadata-searchable; exhaustive legal-discovery indexing and vector
search are intentionally outside this bounded foundation. Results
include `source` and deterministic relevance (exact title 100, title prefix 80, title contains 60,
tag match 40), then tie-break by `updatedAt DESC, id ASC`.

The database reads a fixed leading owner catalog window using the
`(owner_account_id, tenant_id, updated_at DESC, id ASC)` index. The default window is 10,000
metadata-only rows (`DOCUMENT_VAULT_MAX_SEARCH_CATALOG_ENTRIES`, allowed range 100–20,000); it
never falls back to an unbounded query or total-count scan. The service filters/ranks only that
window in memory, so each request is O(W log W) worst case and O(W) memory for fixed W. The
response sets `catalogTruncated: true` when the owner has newer catalog entries beyond that
window; clients should refine a broad term rather than treating it as an exhaustive archive search.

## Optional Qdrant projection

When `DOCUMENT_VAULT_QDRANT_ENABLED=true`, a successful upload projects at most 64 extracted text
chunks (1,600 characters each by default) to the configured Qdrant collection. Each point carries
the document UUID, immutable version, owner UUID, tenant value, chunk UUID, and bounded snippet;
the PostgreSQL row and object store remain authoritative. Projection is best effort and never
turns a committed upload into a failure. A matching upload retry retries the projection, allowing
an operator to repair an unavailable index without duplicating the document. The AI service filters
every query by owner UUID and reports `503 GROUNDED_ANSWER_UNAVAILABLE` when Qdrant is unavailable.
The deterministic embedding is a bounded foundation, not a claim of production semantic quality;
replace it with a reviewed model adapter before relying on recall-sensitive workflows.

## Proof request: `POST /api/v1/documents/{id}/proof-requests`

The endpoint creates a durable owner-scoped `REQUESTED` proof workflow and returns `202 Accepted`
with a `Location` for status polling. It requires exactly one `Idempotency-Key`; matching retries
return the same request UUID, document version, checksum, status, and `Location` with
`Idempotent-Replay: true`. A different document or version under the same actor/tenant/key is a
generic `409`. The request and one `com.lifeos.document.proof.requested.v1` transactional outbox row commit
atomically. When the relay is enabled, leased workers publish the immutable CloudEvent to
`lifeos.document.proof.requested.v1` at least once, with capped exponential backoff and a durable
dead-letter record after the configured attempt limit. The outbox/payload contains only the document
UUID, version, checksum, tenant scope, and event type. No private bytes, object-store reference, or
client key is published.

`GET /api/v1/documents/{id}/proof-requests/{requestId}` returns the same owner-scoped request
state. `REQUESTED` means a separately deployed Trust Ledger worker still must consume the outbox
and obtain an external proof; it is not an anchor claim. If the relay exhausts its bounded publish
attempts, the proof request enters compensating terminal state `FAILED` and the dead-letter row
retains only the immutable digest-only event for operator replay. Missing/cross-owner documents and
request IDs are indistinguishable `404` responses. The durable reservation and document lock have a
five-second transaction bound, and storage/lock failure is `503` with `Retry-After: 1`.

## Status codes

| Status | Meaning |
| --- | --- |
| `200` | Read, metadata replacement, or search. |
| `202` | Proof request accepted into the durable outbox. |
| `201` | Upload, including exact matching upload replay. |
| `400` | Invalid metadata, query, page, idempotency key, or conditional header. |
| `401` | Missing/rejected bearer; `WWW-Authenticate: Bearer` is supplied. |
| `403` | Identity denies an exact registered action. |
| `404` | Missing or outside-caller-scope document; same generic response shape. |
| `408` | Storage staging deadline elapsed. |
| `409` | Same idempotency key was reused for a different command or proof target. |
| `412` | Metadata ETag is stale. |
| `413` | Multipart/request/file byte bound exceeded. |
| `415` | Media type/signature is outside the allow-list. |
| `428` | `If-Match` is absent for metadata mutation. |
| `503` | Identity, audit, storage, or idempotency cannot safely complete. |

## Deployment controls and integrations intentionally pending

Required deployment values are `DOCUMENT_VAULT_DATASOURCE_URL`,
`DOCUMENT_VAULT_DATASOURCE_USERNAME`, `DOCUMENT_VAULT_DATASOURCE_PASSWORD`,
`DOCUMENT_VAULT_IDEMPOTENCY_SECRET`, `DOCUMENT_VAULT_AUDIT_CLIENT_FINGERPRINT_SECRET`, and
`IDENTITY_DOCUMENT_VAULT_WORKLOAD_TOKEN`. Local storage also needs a writable
`DOCUMENT_VAULT_LOCAL_STORAGE_ROOT`; the hardened container prepares
`/var/lib/lifeos/document-vault-objects` for a mounted local-development volume.
For broker delivery, set `LIFEOS_KAFKA_BOOTSTRAP_SERVERS` and
`DOCUMENT_VAULT_PROOF_OUTBOX_RELAY_ENABLED=true`; the topic and ACL must be provisioned by the
deployment. Keep the relay disabled when Kafka is intentionally absent.

FR41 generated summaries and FR54 provider-backed answer quality remain partial: the bounded Qdrant
projection/retrieval and safe insufficient-evidence contract exist, but provider deployment,
embedding quality, consent UX, and broader ingestion remain. FR42 now has a
durable, owner-scoped request/outbox foundation, but Trust Ledger/Besu/Web3j consumption, external
anchoring, retention, and verification status remain separately deployed work; `REQUESTED` never
claims that a proof exists and `FAILED` never silently retries without an operator action.
