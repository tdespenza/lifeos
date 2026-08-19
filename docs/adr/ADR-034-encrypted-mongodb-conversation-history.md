# ADR-034: Explicitly Opt-In Encrypted MongoDB Conversation History

## Status

Accepted for the bounded AI Assistant foundation; production rollout remains deployment- and consent-gated.

## Context

The assistant needs a path for users who explicitly opt into reviewing prior messages, while the
default service must not place raw prompts or completions in PostgreSQL, logs, traces, or an
unbounded process cache. The repository's MongoDB requirement applies to AI conversation history,
but no managed MongoDB, consent UX, or key-management system is available in this codebase.

## Decision

Use an optional `AssistantConversationHistoryStore` boundary. The default implementation discards
content and returns a fail-closed `503` for history reads. When enabled, the Mongo implementation:

- requires a base64-encoded 32-byte AES key and a MongoDB URI that is either `mongodb+srv` or
  loopback-only plaintext MongoDB;
- encrypts content with AES-256-GCM and a fresh 96-bit nonce per message;
- keeps owner/conversation/role/timestamp/TTL metadata bounded and indexes only the owner and
  conversation scope needed for reads;
- enforces a maximum of 100 entries (configurable within a hard bound), a 32 KiB message limit,
  and TTL retention (30 days by default);
- never sends the bearer, raw prompt, or raw output to MongoDB, and writes the PII-redacted prompt
  rather than the original request;
- exposes only an owner-scoped history endpoint and uses the same generic unavailable response for
  disabled, unreachable, or invalid storage.

The local Compose MongoDB profile is loopback-only, unauthenticated development scaffolding. A
production deployment must replace it with authenticated TLS, private networking, backups, managed
key rotation, consent, deletion/export policy, and operational retention evidence.

## Consequences

Users can opt into encrypted bounded history without weakening the default privacy posture. MongoDB
failures are visible as a retryable unavailable response rather than silently losing or replaying
content. The design does not claim cross-service journals, semantic memory, consent UI, or a
production managed Mongo deployment.
