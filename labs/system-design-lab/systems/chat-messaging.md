# Chat and messaging system

Design exercise only — this is a proposed architecture, not a production deployment.

## Requirements

- Authorized members create conversations and send text messages to a bounded member set. Messages
  are durably ordered within one conversation, may arrive at least once to clients, and are retrieved
  after reconnect using a cursor. Cross-conversation ordering is not promised.
- Assume 200,000 connected sessions, 10,000 sends per second, conversations up to 500 members,
  message bodies up to 8 KiB, a 90-day searchable retention policy, and a send-to-online-client p95
  target below 500 ms. These are exercise assumptions, not deployed measurements.
- Send accepts an idempotency key scoped to `(sender, conversation)`. Membership changes are
  versioned, and a removed member cannot fetch new or historical content without a policy grant.
- Voice/video media, arbitrary file transfer, and end-to-end encryption key distribution are outside
  this text-messaging exercise.

## API shape

| Operation | Shape | Contract |
| --- | --- | --- |
| Create/manage conversation | `POST /v1/conversations`, `PATCH /v1/conversations/{id}` | Member authorization; membership mutation uses `If-Match` and records a membership version. |
| Send | `POST /v1/conversations/{id}/messages` | Sender membership plus `Idempotency-Key`; body has text and optional client timestamp; returns assigned sequence number. |
| History | `GET /v1/conversations/{id}/messages?after=&limit=` | Member authorization; keyset cursor, maximum 100 messages, deterministic sequence order. |
| Realtime | `GET /v1/conversations/stream` (WebSocket/SSE) | Authenticated connection with bounded subscriptions and resume cursor; server emits message IDs/sequence plus content permitted to that member. |
| Acknowledge | `POST /v1/conversations/{id}/read-receipts` | Member authorization; monotonic acknowledged sequence only. |

The server rejects a send if the membership version read by the transaction no longer admits the
sender. Clients deduplicate by `(conversationId, sequence)` and may safely resubmit a timed-out send.

## Data model

`conversation(id, tenant_id, state, member_version, created_at)` and `membership(conversation_id,
member_id, role, joined_at, removed_at, version)` are authoritative. `message(conversation_id,
sequence, message_id, sender_id, body_ciphertext, key_id, created_at, tombstoned_at)` uses the
conversation and monotonically assigned sequence as its primary order. `send_dedup(conversation_id,
sender_id, key_hash, message_id, sequence, expires_at)` bounds exact replay. `session_presence`
is ephemeral and separate from message durability. A transactional outbox carries fanout notices,
not raw messages where transport encryption can fetch them directly.

## Scaling and partitioning

Route each conversation to one message partition using a consistent hash of conversation ID; one
partition leader assigns sequence numbers, preserving order without a global counter. Connection
gateways own WebSocket/SSE sessions and subscribe to fanout topics by partition. A large conversation
uses a fanout tree: durable message append once, then bounded recipient batches or online gateway
groups. Offline members pull history rather than accumulating an unbounded per-user queue.

Membership changes and message append remain in the same partition. Moving a hot conversation is a
fenced, briefly paused migration: copy state, replay the partition log, advance a routing epoch, and
reject stale gateway publishes. This preserves its sequence stream while avoiding cross-shard writes.

## Bottlenecks and tradeoffs

Hot group conversations and live fanout are the likely bottlenecks. Partitioning by conversation
gives simple order but one viral group can overload a leader; per-conversation send limits, gateway
fanout limits, and a maximum member count bound the blast radius. A globally ordered event log would
make ordering easy to explain but would unnecessarily serialize unrelated chats. This design chooses
per-conversation order and accepts that delivery to two users can be observed at different times.

Persisting every delivery/read event aids product insight but amplifies writes, so read receipts are
coalesced per member and only the highest contiguous sequence is durable.

## Failure and recovery

The append transaction commits the message and outbox record before acknowledging send. If a gateway
disconnects, the client reconnects with its last contiguous sequence and fetches missing messages;
duplicate fanout is harmless due to sequence deduplication. Outbox relays retry notifications with
jitter, and a stalled partition is visible through relay lag. A send with an uncertain response is
recovered by the dedup record rather than creating a second sequence.

On a partition failure, a replica becomes leader only after a fencing epoch change. Gateways reject
publishes to an old epoch, reconnect clients, and resume from durable offsets. Poison messages or
schema failures move to a quarantine path; they do not cause later conversation messages to vanish.

## Observability

Track append and delivery p95/p99, messages per conversation partition, gateway connection count,
reconnect/resume success, fanout queue depth, outbox lag, duplicate-send replay count, sequence gap
repair count, membership-version rejections, and storage retention purge lag. Traces correlate send,
append, fanout, and receipt with opaque conversation/message hashes. Alert on partition hot spots,
growing fanout lag, reconnect storms, stale routing epochs, or quarantine records.

## Security and privacy

Authenticate connection setup and authorize every conversation/member operation against current
membership. Validate text length/Unicode, attachment omission, subscription count, cursor range, and
client timestamps. Encrypt message bodies at rest with tenant/key separation; redact content and
participant identity from logs/metric labels; apply retention, legal-hold, edit/tombstone, and deletion
rules explicitly. Generic not-found responses prevent conversation/member enumeration, and rate limits
plus abuse reporting protect joins, sends, and reconnects.
