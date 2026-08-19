# Trust Ledger service API

Base URL through the gateway once the route is enabled: `http://localhost:8080`

Service URL for local direct development: `http://localhost:8087`

Management URL (loopback by default): `http://localhost:9087`

The current Trust Ledger service delivers the bounded proof core for FR63 and FR64:
canonical document hashing, deterministic Merkle-proof construction, and local proof verification.
When the opt-in Kafka consumer is enabled, it also durably consumes Document Vault's
`com.lifeos.document.proof.requested.v1` CloudEvent into `lifeos_trust_ledger`, deduplicated by
the request/event UUID and recorded as `PENDING_EXTERNAL_ANCHOR`. An additional opt-in consumer
accepts AI Assistant's hash-only `com.lifeos.ai.audit.hash.requested.v1` CloudEvent and records
`PENDING_EXTERNAL_ANCHOR` in a separate table, with event-id deduplication and conflict rejection.
It never stores prompt/completion content or calls Besu. The service does not persist document bytes,
expose a public account selector, contact Besu, or claim that a root is anchored unless the explicit
anchor workflow below is enabled. Besu/Web3j anchoring is now a durable opt-in workflow; owner-scoped
anchor receipt status is available for credential verification, while Kafka operation, the external
AI-audit anchor worker, and full anchored credential issuance/verification remain separate follow-up
work. Completed-goal certificate issuance (FR68) now has a reviewed owner-scoped projection
contract; external confirmation still requires an enabled Besu/Web3j adapter.

## Authentication and authorization

Every endpoint requires `Authorization: Bearer <access-token>`. The service validates the bearer
through identity-service with the `trust-ledger-service` workload credential, then requests the
exact V2 personal-tenant collection capability for the operation. The resource facts are entirely
service-derived: resource type `trust-ledger`, validated account tenant, and an empty attribute
map. Document content, document identifiers, and caller-supplied ownership facts never enter the
authorization decision.

| Endpoint | Identity V2 action |
| --- | --- |
| `POST /api/v1/trust/document-proofs` | `trust:document-proof-create` |
| `POST /api/v1/trust/merkle-proofs` | `trust:merkle-proof-create` |
| `POST /api/v1/trust/merkle-proofs/verify` | `trust:proof-verify` |
| `POST /api/v1/trust/document-proof-requests/{requestId}/anchor` | `trust:anchor-create` |
| `GET /api/v1/trust/document-proof-requests/{requestId}/anchor` | `trust:credential-verify` |
| `POST /api/v1/trust/document-proof-requests/{requestId}/verify` | `trust:credential-verify` |
| `POST /api/v1/trust/goal-certificates` | `trust:goal-certificate-create` |
| `GET /api/v1/trust/goal-certificates/{certificateId}` | `trust:credential-verify` |
| `POST /api/v1/trust/goal-certificates/{certificateId}/verify` | `trust:credential-verify` |

Media uses one internal workload-authenticated command for digest-only session-summary proofs:
`POST /api/v1/internal/trust/session-summary-anchors`. It accepts the Media workload identity,
Idempotency-Key, validated subject proof, artifact UUID/version, and a 64-character SHA-256 digest;
it never accepts transcript or summary content. The exact V2 capability is
`trust:session-summary-anchor`. A matching durable retry replays its result, while an external
anchor failure returns a retryable unavailable response and leaves the request reconcilable by key.

The public proof operations are pure computation: they do not create a public server-side record,
so the same valid retry recomputes the same result without an `Idempotency-Key`. The Kafka consumers
are deliberately separate from that API and use immutable CloudEvent UUIDs as durable inbox keys.
They are disabled by default; enabling them requires the Trust Ledger database and the versioned
topics plus `.DLT`. Malformed records receive two one-second retries and then go to the `.DLT` topic.
AI commitments use a distinct topic/group from document proofs.
Anchor and certificate mutations require a durable idempotency key and use a short database claim
before the external call. The current anchor endpoint is deliberately separate from the pure proof
operations and does not claim a successful anchor until a chain receipt is confirmed.

### `POST /api/v1/trust/document-proof-requests/{requestId}/anchor`

Requires `Idempotency-Key` and the `trust:anchor-create` Identity V2 capability. The request UUID
is the durable proof projection key; the idempotency hash is persisted with that row, and a
different key returns `409 IDEMPOTENCY_KEY_CONFLICT`. Concurrent callers are serialized through a
short database claim (`SUBMITTING`), while the external RPC call runs outside the transaction.
Successful receipt confirmation returns `CONFIRMED` with transaction hash and block number.
Disabled, unavailable, or failed Besu calls reset the row to `PENDING_EXTERNAL_ANCHOR` and return
retryable `503 ANCHOR_UNAVAILABLE`; no private document data or owner identifiers are ABI-encoded.

### `GET /api/v1/trust/document-proof-requests/{requestId}/anchor`

Requires the `trust:credential-verify` capability. It returns only the caller's durable request
state, transaction hash, block number, and update timestamp. Missing and cross-owner request IDs
return the same retryable unavailable response; the endpoint never discloses another account's
document or checksum.

### `POST /api/v1/trust/document-proof-requests/{requestId}/verify`

The caller supplies the expected document UUID, immutable version, and SHA-256 checksum. The
service compares those facts with the owner-scoped durable projection and returns `VALID` only
after a confirmed external receipt. A matching pending projection is
`INDETERMINATE_EXTERNAL_ANCHOR`; a mismatch is `INVALID`.

### `POST /api/v1/trust/goal-certificates`

Requires `trust:goal-certificate-create` and a durable `Idempotency-Key`. The request contains only
a goal UUID. Trust Ledger calls Task/Goal's workload-authenticated internal projection, which
rechecks the caller's Identity proof, owner/tenant scope, and `COMPLETED` lifecycle state before
returning the immutable goal version and completion timestamp. The certificate digest is
`SHA-256("lifeos-goal-certificate-v1\\n" + goalId + "\\n" + goalVersion + "\\n" + completedAt)`;
title, notes, task links, and other private goal data are excluded. The durable response is
`PENDING_EXTERNAL_ANCHOR` when Besu is disabled (or briefly `SUBMITTING` during one bounded anchor
attempt) and becomes `CONFIRMED` only after a chain receipt.
Matching retries replay the same certificate; a key reused for another goal returns `409`.

### `GET /api/v1/trust/goal-certificates/{certificateId}`

Requires `trust:credential-verify` and returns only the caller's certificate id, goal UUID,
immutable version/time, derived digest, and optional transaction receipt. Missing and cross-owner
IDs return a generic unavailable response.

### `POST /api/v1/trust/goal-certificates/{certificateId}/verify`

The caller supplies the goal UUID, immutable version, completion timestamp, and 64-character
certificate digest. Trust Ledger recomputes the domain-separated digest and compares the
owner-scoped durable record. It returns `VALID` only when the facts match and a receipt-confirmed
anchor exists; a matching pending or submitting certificate is `INDETERMINATE_EXTERNAL_ANCHOR`,
never valid by fallback. A mismatched or tampered presentation returns `INVALID`.

| Status | Code | Meaning |
| --- | --- | --- |
| `400` | `INVALID_PROOF_INPUT` | Invalid metadata, digest, ordering, duplicate leaf, proof path, or bounded input |
| `401` | `AUTHENTICATION_REQUIRED` | Missing or invalid bearer credential |
| `403` | `AUTHORIZATION_DENIED` | Identity denied the exact Trust Ledger action |
| `413` | `DOCUMENT_TOO_LARGE` | Multipart input exceeded the configured maximum |
| `422` | `DOCUMENT_UNREADABLE` | The upload stream could not be read safely |
| `503` | `AUTHORIZATION_UNAVAILABLE` | Identity validation or a current policy decision is unavailable; retry shortly |

Responses and logs omit upload content, filenames, token values, document identifiers, and private
metadata. Every request gets a validated or generated `X-Correlation-ID`; Prometheus exposes
`lifeos.trust.proof.operation` timers with only `operation` and `outcome` labels.

## `POST /api/v1/trust/document-proofs`

Consumes `multipart/form-data` with:

- `content`: a non-empty document stream, bounded to 100 MiB by default;
- `mediaType`: a safe media-type token such as `application/pdf`;
- `proofPurpose`: a safe semantic token such as `integrity`.

The service streams content through standard SHA-256 in a fixed 16 KiB buffer. The digest input is
domain-separated and binds the canonical lower-case metadata, but excludes names, account IDs,
paths, tags, source URLs, and arbitrary document metadata. It therefore uses O(B) time and O(1)
content memory for B upload bytes.

```json
{
  "algorithm": "SHA-256",
  "digest": "b4f4...64-lowercase-hex-characters...",
  "contentBytes": 1048576
}
```

## `POST /api/v1/trust/merkle-proofs`

Consumes an ordered, unique batch of 1–10,000 SHA-256 document digests. The documented format
uses `SHA-256(0x00 || documentDigest)` for leaves, `SHA-256(0x01 || left || right)` for internal
nodes, and duplicates an odd node as its own right sibling. Input order is intentionally part of
the proof contract.

```json
{
  "documentDigests": [
    "1111111111111111111111111111111111111111111111111111111111111111",
    "2222222222222222222222222222222222222222222222222222222222222222"
  ]
}
```

The response has algorithm `SHA-256-MERKLE-v1`, the root, and one ordered sibling path per input
digest. Building takes O(N) tree storage and O(N) hashing for N leaves; each returned path is
O(log N).

## `POST /api/v1/trust/merkle-proofs/verify`

Consumes a root, original digest, original zero-based leaf index, and at most 32 sibling steps.
Verification reconstructs the root in O(P) time and O(1) additional memory for P proof steps. A
well-formed but altered root, leaf, side, or sibling returns `{ "valid": false }`; malformed or
over-bound input is rejected as `400`, never interpreted as a valid proof.

```json
{
  "valid": true
}
```

## Deployment controls

Required secret: `IDENTITY_TRUST_LEDGER_WORKLOAD_TOKEN`, which must match identity-service's
registered `trust-ledger-service` workload credential. Non-loopback Identity endpoints must use
HTTPS. Relevant resource controls are `TRUST_LEDGER_MAX_DOCUMENT_BYTES`,
`TRUST_LEDGER_MAX_MERKLE_LEAVES`, `TRUST_LEDGER_INBOUND_REQUEST_TIMEOUT`, and the identity
connect/read/concurrency bounds. The Tomcat listener applies the inbound timeout to initial,
keep-alive, and request-body upload reads, while the hashing implementation independently enforces
the configured byte limit. Besu enabling additionally requires `TRUST_LEDGER_BESU_PRIVATE_KEY`,
`TRUST_LEDGER_BESU_CONTRACT_ADDRESS`, and `TRUST_LEDGER_BESU_RPC_URL`; non-loopback RPC URLs must
use HTTPS. The local HTTP RPC URL is loopback-only development scaffolding and is not a private
network or signing-key management solution.
