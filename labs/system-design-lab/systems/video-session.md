# Video session system

Design exercise only — this is a proposed architecture, not a production deployment.

## Requirements

- Authorized hosts create bounded real-time sessions; authorized participants join with a short-lived
  token, exchange signaling, publish/subscribe media through a selected relay, and leave or expire.
  Signaling/session state is durable enough for recovery; media packets are not persisted by default.
- Assume 20,000 concurrent sessions, up to 25 participants per standard session, 5,000 joins per
  minute, 10-second join setup target, and 15-second heartbeat expiry. These are design inputs, not
  a deployment capacity claim.
- A session has a host-controlled admission policy, a versioned participant roster, and a bounded
  reconnect window. Recording, transcription, and arbitrary peer-to-peer data channels are out of
  scope unless separately approved.
- Session control must remain available enough to end/revoke a participant even if one media relay
  is unhealthy; no service silently reports a media stream as healthy without relay evidence.

## API shape

| Operation | Shape | Contract |
| --- | --- | --- |
| Create | `POST /v1/video-sessions` | Host auth and `Idempotency-Key`; body has scheduled expiry, maximum participants, and admission mode; returns session ID/version. |
| Join token | `POST /v1/video-sessions/{id}/join-tokens` | Member/guest grant is checked; returns a single-use, short-lived signed token bound to session, role, and roster version. |
| Signaling | `POST /internal/video-sessions/{id}/signals` or authenticated WebSocket | Relay/gateway verifies token, size/rate limits SDP/ICE messages, and routes only within the session. |
| Control | `PATCH /v1/video-sessions/{id}` | Host role plus `If-Match`; admits, removes, ends, or changes future policy. |
| Health | `POST /internal/relays/{id}/heartbeats` | mTLS relay report of bounded aggregate capacity and session allocation state. |

Join tokens are not bearer access to a recording or metadata outside the session. A disconnect must
rejoin with a current roster/token, so removed participants cannot use an old session connection.

## Data model

`video_session(id, tenant_id, host_id, state, max_participants, roster_version, expires_at,
relay_region, version)` is authoritative control state. `participant(session_id, principal_id, role,
state, joined_at, disconnected_at, token_nonce_hash)` records admission and lifecycle. `relay_assignment
(session_id, relay_id, epoch, assigned_at, health_state)` uses an epoch fencing token. `session_event`
holds a privacy-minimized control audit trail; raw SDP, ICE candidates, and media are short-lived
transport data rather than durable database fields.

## Scaling and partitioning

Route control operations by session ID. A placement service selects a relay region close to the first
participants and with reserved capacity; it treats relay allocation as a leased resource. Media SFUs
scale independently from signaling gateways and publish only aggregate capacity/health to control
plane shards. Large sessions use a different class with lower publishing limits or a broadcast fanout
topology; the standard session cap prevents a single room from overwhelming one relay.

Session state has a designated control leader per session hash. Relay reassignment increases the
assignment epoch, issues new transport credentials, and asks clients to renegotiate; it never assumes
media can be transparently moved mid-packet without a reconnect.

## Bottlenecks and tradeoffs

Relay egress bandwidth and ICE/signaling bursts are the first constraints. An SFU lowers client
uplink requirements compared with full mesh but centralizes egress and cost; full mesh avoids an SFU
for tiny rooms but grows quadratically and is deliberately excluded beyond a small limit. Regional
placement lowers latency but may reduce a participant's failure alternatives, so admission keeps a
reserve capacity threshold and a cross-region fallback policy.

Persisting all signaling would improve forensic replay but exposes network metadata and grows rapidly.
This design retains only redacted control events and bounded relay health summaries.

## Failure and recovery

Control writes use version checks and a durable outbox for relay commands. A relay heartbeat timeout
marks only its assigned sessions as `RECONNECT_REQUIRED`, selects a healthy relay, fences the old
assignment, and delivers bounded reconnect instructions. Participants use exponential reconnect
backoff within a fixed window; the host can end a stuck session. A failed signaling gateway is
recoverable because token/roster checks occur on reconnect against durable state.

Expired session leases reclaim relay slots. Duplicate control commands are keyed by session/version;
stale relay reports cannot resurrect a fenced assignment. Media-quality degradation is reported, not
reinterpreted as a control-plane success. A reconciliation job compares live relay allocations with
durable assignments and safely releases orphaned leases.

## Observability

Measure join success/setup latency, signaling request errors, active sessions/participants, relay CPU
and egress utilization, packet loss/jitter/round-trip-time distributions, reconnect/renegotiation
rate, heartbeat age, allocation failures, orphan-release count, and session-end reason. Trace control
requests using session and relay hashes, never SDP or IP addresses. Alert on relay saturation, a
region's join failure rate, heartbeat loss, sustained poor media quality, or unexpected allocation
leaks.

## Security and privacy

Authorize host/member/guest roles against tenant/session scope and issue audience-bound, expiring,
single-use join tokens. Use mTLS between control plane and relays, validate signaling size/schema and
ICE candidate policy, rate-limit token minting/join attempts, and revoke tokens on roster changes.
Encrypt control data at rest, minimize network metadata retention, redact addresses and session names
from telemetry, and document recording/consent as a separate feature with its own retention/legal
requirements. Return generic errors for inaccessible sessions to prevent enumeration.
