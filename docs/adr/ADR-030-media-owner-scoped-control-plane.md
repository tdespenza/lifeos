# ADR-030: Media owner-scoped control plane and fail-closed adapters

## Status

Accepted — implemented as an isolated `media-service` foundation. Gateway routing and local
Compose database/environment provisioning are complete; application-container deployment,
object-store/worker/SFU adapters, and a recording pipeline remain separate work.

## Context

FR43–44 need safe live-session scheduling and joining without fabricating WebRTC or media
processing. Video metadata/upload and HLS access mechanics are useful preparatory infrastructure,
but FR41–42 document summary/proof work belongs to Document Vault. Source bytes, HLS paths, and
signaling credentials make Media an authorization and storage boundary.

## Decision

- Media owns PostgreSQL asset/session metadata, durable idempotency snapshots, and redacted audit
  facts. Hibernate validates and Flyway V1 has an H2 test mirror. Raw bytes and generated object
  references remain behind an object-store interface.
- Asset lifecycle is two-step: `media:asset-create` creates `AWAITING_UPLOAD`; then
  `media:asset-upload` authorizes one source upload on an existing owner object. This matches
  Identity-v2 resource shapes instead of authorizing upload against an unpersisted object.
- Every retryable mutation takes a bounded idempotency key. Upload, session update, and cancel
  also require a strong numeric ETag. The service retains only an HMAC key/request fingerprint and
  commits a successful response snapshot atomically with the database write. Rejected commands
  remove a pending reservation.
- Media validates the bearer, rejects missing/cross-owner local resources with one generic
  no-disclosure response, and then validates a closed `media:*` Identity-v2 action for an owner
  object. Personal tenancy is exactly account UUID string.
- `LOCAL_DEVELOPMENT` storage validates MP4/WebM magic bytes, streams in a fixed buffer, caps the
  source at 50 MiB / request at 51 MiB, observes a 60-second deadline, and uses generated paths.
  Production default `EXTERNAL_OBJECT_STORE_REQUIRED` fails closed until a reviewed adapter exists.
- HLS is access modeled, not produced. Owner reads require `HLS_READY`, strict segment names, and
  bounded private/no-store streaming. No public completion route exists; a future authenticated
  worker must validate artifacts before it can transition an asset to ready.
- Sessions are owner-only, schedule-bounded, and joinable only in a ten-minute pre-start through
  scheduled-end window. `LOCAL_DEVELOPMENT` returns a short signed test permit, not WebRTC.
  `EXTERNAL_SFU_REQUIRED` fails closed until a workload-authenticated adapter is installed.
- A bounded row-locked scheduler persists due scheduled sessions as `ENDED`; each pass claims at
  most 100 rows and uses `SKIP LOCKED` semantics so replicas do not wait on one another.

## Consequences

- A storage promotion followed by an indeterminate DB commit can leave a generated orphan; Media
  retains it instead of risking deletion of a committed reference. A future reconciliation policy
  is required.
- Normal uploads cannot become HLS-ready yet. This is intentional rather than a false transcoding
  claim. No ffmpeg worker, HLS CDN, SFU, TURN/ICE/DTLS/SRTP, recording, provider deployment, or
  Document Vault proof/summarization is claimed.
- Gateway applies exact streaming behavior only to the 51 MiB upload and two bounded HLS GET
  routes. All other `/api/v1/media/**` routes remain ordinary JSON proxying.

## Verification

H2 integration/contract tests cover owner-local scope, metadata/source replay, ETags, session
lifecycle, join permits, redacted API fields, HLS-not-ready behavior, and migration validation.
Local storage tests cover binary validation and traversal denial. PostgreSQL 17 Testcontainers
proves concurrent matching creates converge on one durable asset/snapshot and skips only when
Docker is unavailable.
