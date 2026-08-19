# Document storage system

Design exercise only — this is a proposed architecture, not a production deployment.

## Requirements

- An authorized owner uploads an allowed media type, reads owner-scoped metadata, changes versioned
  metadata, searches a bounded metadata projection, and requests a short-lived content download URL.
  Database rows store opaque object references, checksum, size, and content metadata—not document
  bytes.
- Assume 2,000 upload requests per minute, individual file cap of 10 MiB, 100 million metadata rows,
  30-second upload deadline, 30-day temporary-object expiry, and p95 metadata read below 100 ms.
  These are design inputs, not a production deployment claim.
- Upload and metadata mutation use idempotency keys; mutable metadata uses strong ETags/`If-Match`.
  Antivirus/classification processing is asynchronous and an unscanned object is never downloadable.
- Full-text indexing, public sharing, legal hold, proof anchoring, and AI extraction are separate
  integrations rather than implicit promises of this storage core.

## API shape

| Operation | Shape | Contract |
| --- | --- | --- |
| Upload | `POST /v1/documents` multipart | Owner auth and `Idempotency-Key`; validates filename/media type/size while streaming; returns `201` metadata and ETag after durable object promotion. |
| Metadata | `GET /v1/documents/{id}`, `PUT /v1/documents/{id}/metadata` | Owner/tenant authorization; update requires `If-Match`; response excludes object-store reference. |
| Search | `GET /v1/documents/search?q=&cursor=&limit=` | Owner-filtered, validated metadata terms, page size at most 50, fixed catalog/read budget. |
| Download | `POST /v1/documents/{id}/download-grants` | Owner auth; only `READY` content receives a short-lived, single-object signed URL. |
| Scan callback | `POST /internal/document-scans/{id}` | mTLS scanner identity and idempotent checksum/status update. |

The service responds generically for missing and cross-owner IDs. Replaying an upload key with a
different canonical metadata/content checksum is `409`; the exact stored response is returned on a
matching replay.

## Data model

`document(id, owner_id, tenant_id, object_reference, checksum_sha256, byte_size, media_type,
scan_state, title, tags, classification, version, created_at, updated_at, deleted_at)` is source of
truth metadata. `object_reference` is an opaque UUID-derived key, never a filesystem path supplied
by a client. `document_command(owner_id, key_hash, fingerprint, response_snapshot, state, expires_at)`
handles idempotency and recovery. `object_staging(stage_id, object_key, expires_at)` supports upload
cleanup; `document_event` is an outbox for scanner/index/lifecycle consumers. Object bytes are held
only in object storage and encrypted with a per-tenant/key-encryption-key reference.

## Scaling and partitioning

Metadata tables partition by tenant hash and document UUID range; owner/tenant/update indexes support
keyset listing and bounded search. Object storage distributes keys by a random UUID prefix rather than
tenant name, avoiding path traversal and hot directories. Upload application nodes stream to a
staging object with strict byte/time bounds, then atomically promote it after metadata commit or via
a recoverable two-phase state. Scanner and lifecycle workers consume document outbox partitions by
document ID, allowing content processing to scale independently from metadata reads.

Large tenants may receive dedicated metadata partitions while object access stays globally balanced.
Rebalancing copies metadata under a routing epoch and relies on opaque references, so object bytes do
not need to move with the database shard.

## Bottlenecks and tradeoffs

Object-store bandwidth, scanner throughput, and metadata search selectivity are likely bottlenecks.
Streaming uploads prevent application-memory exhaustion but make retry/resume design harder; a client
may use a new idempotency key only after the original result is known or expired. Storing bytes in the
database simplifies atomicity but inflates backups/replication, so opaque object references are chosen
with explicit orphan/cleanup reconciliation. Strong metadata ETags avoid lost updates but require a
caller to refresh after concurrent changes.

Search scans a bounded owner catalog or a dedicated approved index; it never performs an unbounded
cross-tenant metadata scan. Content availability is delayed by scan state, trading immediate download
for malware/control enforcement.

## Failure and recovery

Upload staging, metadata creation, and promotion have explicit states. If the request disconnects or
deadline expires, staging is deleted best effort and an expiry sweeper removes leftovers. If promotion
succeeds but the database commit outcome is uncertain, the idempotency record plus checksum can finish
or safely reconcile the command; the service does not blindly delete a potentially referenced object.
Scanner callbacks are idempotent and a lease expiry reschedules stuck scans. Object-store/read errors
return retryable errors rather than an invented download grant.

A reconciliation job lists only bounded staging/object prefixes, compares them with metadata states,
and repairs orphaned records according to an age policy. Backups restore metadata and object version
references together; restore validation samples checksums before making documents downloadable.

## Observability

Track upload bytes/duration/outcomes, inbound-cap/deadline rejections, staging age/count, promotion
latency/errors, idempotency replay/conflict/recovery count, scan queue/lease age, `READY` transition
lag, metadata query latency/candidate count, download-grant issuance, object checksum mismatch, and
orphan cleanup. Trace with correlation and document hashes, never title/tags/object keys. Audit owner,
action, policy outcome, document hash, and immutable version. Alert on old staging objects, scan lag,
promotion failures, unusual download-grant rate, or cleanup backlog.

## Security and privacy

Authenticate and owner/tenant-authorize every route; authorize again before issuing a download URL.
Allow-list media type with byte-signature verification, cap request/files/metadata, normalize names,
and generate all object keys server-side. Encrypt objects and metadata, rotate keys, presign only one
object for a short TTL, deny unscanned/quarantined content, and protect scanner callbacks with mTLS.
Do not log bytes, titles, tags, URLs, or object references; maintain retention/deletion/tombstone
workflows and return non-enumerating errors outside an authorized owner scope.
