# media-service API and bounded media-control contract

Gateway base URL: `http://localhost:8080`

Direct development URL: `http://localhost:8089`; loopback management: `http://localhost:9089`.

`media-service` is the isolated FR43–52 foundation: owner-scoped video metadata, bounded source
upload control, private HLS-read policy, scheduled live-session metadata, idempotency snapshots,
redacted audit facts, and an explicit post-session transcript/summary/action-item artifact. The
post-session endpoint accepts only a bounded transcript (it does not claim speech recognition),
then stores a deterministic local summary and up to sixteen prefixed action items. It does **not**
implement Document Vault FR41–42, a production HLS worker callback, object-store vendor
integration, a WebRTC/SFU server, participant invitations, recording, or a CDN. An explicit
`LOCAL_DEVELOPMENT` processing mode can run a bounded local ffmpeg process against a promoted
source and atomically publish validated private HLS artifacts; the default remains fail-closed
`EXTERNAL_WORKER_REQUIRED`.

## Authentication, ownership, and errors

Every route requires `Authorization: Bearer <access-token>`. Media validates the bearer through
Identity as workload `media-service`, rejects missing/cross-owner local objects without disclosure,
then requires its registered policy-v2 action for an owner object. The tenant is always the
authenticated account UUID string; client input cannot select an owner or tenant.

| Capability | Identity action |
| --- | --- |
| Asset create/list/read/upload | `media:asset-create`, `media:asset-list`, `media:asset-read`, `media:asset-upload` |
| HLS manifest/segment read | `media:hls-manifest-read`, `media:hls-segment-read` |
| Session create/list/read/update/cancel/join | `media:session-create`, `media:session-list`, `media:session-read`, `media:session-update`, `media:session-cancel`, `media:session-join` |
| Post-session processing/read | `media:session-update`, `media:session-read` |
| Confirmed follow-up task command | `media:session-update` plus a workload-authenticated TaskGoal command |

| Status | Meaning |
| --- | --- |
| `400` | Invalid JSON, bounds, ETag, idempotency key, or schedule. |
| `401` / `403` | Authentication missing/invalid or Identity capability denial. |
| `404` | Missing or cross-owner local resource; both have the same generic body. |
| `409` | Changed idempotency-key reuse, invalid lifecycle, not-joinable session, or HLS not ready. |
| `412` / `428` | Stale `If-Match` / missing `If-Match` for a versioned mutation. |
| `413` / `415` | Upload exceeds the bound / source is not verified MP4 or WebM. |
| `503` | Identity, audit, idempotency, storage, or signaling is unavailable. |

Redacted audit records contain only actor/session and target identifiers, safe action/outcome/reason,
correlation ID, and a keyed client-address digest. They exclude media titles/bytes, paths,
checksums, filenames, tokens, idempotency keys, and signaling credentials.

## Asset lifecycle and upload

### `POST /api/v1/media/assets`

```http
Authorization: Bearer <access-token>
Idempotency-Key: <16-128 character opaque key>
Content-Type: application/json

{"title":"Private coaching clip"}
```

Returns `201 Created`, `Location`, `ETag: "0"`, and `AWAITING_UPLOAD`. A matching retry returns
the retained original status/body/location plus `Idempotent-Replay: true`; changed input for that
key is `409`.

### `PUT /api/v1/media/assets/{assetId}/source`

This is the only large request route. It requires bearer, `Idempotency-Key`, strong
`If-Match: "0"`, and one multipart `file` part. It accepts declared `video/mp4` or `video/webm`
only after checking the `ftyp` or EBML binary prefix; filename, extension, client checksum, and
provider path are ignored. It streams with a fixed 16 KiB buffer, server-generates paths, caps
source bytes at **50 MiB**, whole request at **51 MiB**, and has a **60-second** local deadline.

Success is `200`, `ETag: "1"`, and `STORED_AWAITING_EXTERNAL_PROCESSING`. Matching source retry
with the original ETag/key/content replays after the status changed; a distinct upload command is
rejected by the explicit status transition. `GET /api/v1/media/assets?limit={1..200}` uses a
bounded owner-indexed `createdAt,id` page. `GET /api/v1/media/assets/{assetId}` returns a strong
ETag. Neither response includes an object reference or checksum.

## HLS read model

No current adapter creates HLS. A future reviewed worker may mark an asset `HLS_READY` only after
validating private artifacts through a separately designed authenticated completion path. Normal
uploads therefore remain `STORED_AWAITING_EXTERNAL_PROCESSING`; Media does not pretend to
transcode.

| Exact protected stream route | Bound | Content type |
| --- | --- | --- |
| `GET /api/v1/media/assets/{assetId}/hls/master.m3u8` | 1 MiB | `application/vnd.apple.mpegurl` |
| `GET /api/v1/media/assets/{assetId}/hls/segments/{segmentName}` | 25 MiB | `.m4s`: `video/iso.segment`; otherwise `video/mp2t` |

Both authorize the owner before open, use `Cache-Control: private, no-store`, and stream service
responses without buffering. Segment names must match `[A-Za-z0-9][A-Za-z0-9._-]{0,127}`, end in
`.m4s` or `.ts`, and cannot contain `..`; arbitrary paths, manifest URLs, redirects, Range, and
CDN behavior are not accepted or claimed. Gateway streams only these two GET routes, not all
Media.

## Scheduled live sessions

`POST /api/v1/media/sessions` requires bearer plus `Idempotency-Key` and accepts bounded JSON:

```json
{
  "kind":"COACHING",
  "title":"Practice session",
  "scheduledStartAt":"2026-08-18T18:00:00Z",
  "scheduledEndAt":"2026-08-18T18:30:00Z",
  "timeZone":"America/Chicago"
}
```

Start must be future and within 366 days; duration is 1 minute through 4 hours by default. Create
returns `201`, location, and `ETag: "0"`. Every session response also exposes `remainingSeconds`
and a one-minute `endWarning`; once the scheduled deadline passes, the derived status is `ENDED`
and the join window closes without extending the session. A bounded row-locked scheduler persists
the `ENDED` transition every second (up to 100 due rows per pass), with `SKIP LOCKED` behavior
allowing replicas to make progress without duplicate expiry work. `GET /api/v1/media/sessions?limit={1..200}` and
`GET /api/v1/media/sessions/{sessionId}` are owner scoped. `PUT /{sessionId}` and
`POST /{sessionId}/cancel` require both Idempotency-Key and If-Match and permit only
`SCHEDULED → CANCELLED` cancellation.

`POST /api/v1/media/sessions/{sessionId}/join` is intentionally non-mutating: only the owner can
join from ten minutes before start through scheduled end and receives a short-lived room-scoped
credential. There is no client-supplied participant ID. `LOCAL_DEVELOPMENT` creates a signed local
test permit only; it is not WebSocket signaling, TURN, ICE, DTLS, SRTP, WebRTC, or an SFU.
Production defaults to `EXTERNAL_SFU_REQUIRED`, so join is `503` until a reviewed adapter exists.

## Post-session transcript artifact

`POST /api/v1/media/sessions/{sessionId}/post-session` is available only after the durable session
status is `ENDED`. It requires `Idempotency-Key` and a bounded JSON body:

```json
{"transcript":"We reviewed priorities. ACTION: Send the plan. TODO: Book the next session."}
```

The service normalizes the supplied text, writes a deterministic `LOCAL_DETERMINISTIC_TEXT`
summary, extracts at most sixteen `ACTION:`, `TODO:`, or `FOLLOW-UP:` lines, and persists one
owner-scoped artifact. A matching retry returns the exact saved `201` snapshot with
`Idempotent-Replay: true`; changed input for the same key is rejected. `GET` on the same path
returns the saved transcript, summary, action items, processing state, and ETag. Raw audio,
provider credentials, confidence claims, and timing metadata remain outside this local boundary.
A future reviewed transcription worker can replace the input boundary.

### `POST /api/v1/media/sessions/{sessionId}/post-session/tasks`

This is the only path that turns an extracted item into a TaskGoal record. It requires the owner's
bearer token, `If-Match: "<artifact-version>"`, one `Idempotency-Key`, and a body such as:

```json
{"actionItem":"Send the plan.","priority":2,"dueAt":"2026-08-25T17:00:00Z"}
```

The action text must exactly match one item in the durable artifact. Media re-authorizes
`media:session-update`, then sends only the validated Identity subject proof (never the raw bearer)
to TaskGoal over a separate `media-service` workload credential. TaskGoal re-authorizes
`task:create` and derives owner/tenant facts locally. Both services retain a durable idempotency
snapshot keyed by the session/action-item pair; retries—even with a fresh HTTP key—therefore return
the same `201`, `Location: /api/v1/tasks/{taskId}`, task body, and strong task ETag without creating
a duplicate. A changed priority/deadline for an already confirmed item is a generic `409`. Unknown items are `400`, stale artifact ETags
are `412`, TaskGoal denial is `403`, and an unavailable TaskGoal boundary is `503` with
`Retry-After: 1`. No task is created implicitly while processing a transcript.

### `POST /api/v1/media/sessions/{sessionId}/post-session/anchor`

This optional command requests an integrity proof for the immutable post-session artifact. It
requires the owner's bearer credential, `If-Match: "<artifact-version>"`, and one
`Idempotency-Key`. Media performs its normal owner/tenant check and authorizes
`media:session-update` before computing a domain-separated SHA-256 digest over the artifact ID,
version, deterministic summary, and action-item JSON. The raw transcript, summary text, action
items, bearer token, and storage references are never sent to Trust Ledger.

Trust Ledger receives only the digest and validated subject proof over its workload-authenticated
internal boundary. A successful response is `200` with `state: CONFIRMED` only after an external
receipt; matching retries replay the same request ID/digest/receipt with `Idempotent-Replay: true`.
Provider-disabled or unavailable anchoring returns retryable `503 ANCHOR_UNAVAILABLE`; a changed
key/input or stale artifact version returns `409`/`412`. The default Trust Ledger adapter remains
fail-closed until reviewed Besu/Web3j network and signing-key controls are deployed.

## Operations and pending deployment

Production defaults to `EXTERNAL_OBJECT_STORE_REQUIRED` and `EXTERNAL_WORKER_REQUIRED`; missing
adapters fail closed. Local storage and `MEDIA_PROCESSING_MODE=LOCAL_DEVELOPMENT` are development/
test-only and require a reviewed local ffmpeg installation. Processing uses generated paths,
no shell, a 60-second deadline, a two-job semaphore, and manifest/segment size/type validation.
A DB failure after external promotion can leave a generated orphan, which needs a future
reconciler rather than unsafe deletion. Media runs virtual threads, bounded inbound concurrency,
Prometheus/OTLP/ECS, and loopback health endpoints.

Gateway provides separate exact streaming rules: the 51 MiB upload `PUT` has a 75-second upstream
deadline and the two HLS GET routes have bounded response streaming. All other routes are ordinary
small JSON. Required startup values are
`MEDIA_DATASOURCE_*`, `MEDIA_IDEMPOTENCY_SECRET`, `MEDIA_AUDIT_CLIENT_FINGERPRINT_SECRET`,
`MEDIA_DEVELOPMENT_SIGNALING_SECRET` (each minimum 32 bytes), and
`IDENTITY_MEDIA_WORKLOAD_TOKEN`. The non-root image is
`infrastructure/docker/media-service.Dockerfile`.

The local Compose support stack provisions `lifeos_media` and the tracked `.env.example` names the
required values, but it does not run application containers. To exercise upload or join locally,
an untracked environment file must deliberately opt into `MEDIA_STORAGE_MODE=LOCAL_DEVELOPMENT`
and `MEDIA_SIGNALING_MODE=LOCAL_DEVELOPMENT`; production remains fail closed until reviewed
object-store, worker, and SFU adapters are deployed.
