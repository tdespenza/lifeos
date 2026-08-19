# Why MongoDB for Journals and AI Conversations

Quick honesty check up front: Profile and AI Assistant now have bounded encrypted MongoDB adapters
with owner-scoped journal/history APIs, but both are disabled by default. Consent UX, managed
MongoDB, and key-rotation workflows are not built. What follows explains the boundary and the
remaining operational work rather than claiming a production Mongo deployment.

The reasoning starts from the shape of the data, not from "let's use two databases because that's what big companies do." Structured entities — accounts, goals, calendar events, finance transactions — have stable schemas and benefit from relational integrity and joins, so those stay in Postgres as the system of record. Journals and AI conversation history are a different animal. A journal entry picks up new optional fields over time (mood tags, location, attachments). An AI conversation turn is a tree — nested tool calls, retrieved-context snippets, provider-specific metadata — and that structure changes shape every time you add a model provider or a new capability. Trying to force that into normalized tables means either a wall of join tables or a JSON blob column with no real query ergonomics. MongoDB's document model just matches how this data actually looks: fetch-a-conversation and fetch-a-journal-entry are the dominant access patterns, not cross-entity joins.

I did consider keeping it simpler — JSONB columns in the existing Postgres databases, avoiding a second stateful technology entirely. That's a real option and I don't dismiss it; a second database is genuinely more operational surface (its own backup/restore story, its own monitoring, its own on-call skill requirement). But JSONB's indexing and partial-update ergonomics get awkward fast for deeply nested, frequently-mutated structures like multi-turn conversations, and schema-on-read in Mongo means a new provider's token-usage shape just... shows up, no migration. I also looked at Cassandra (wrong tool — it's built for write-throughput at a scale this personal-data workload doesn't need) and Elasticsearch as a primary store (rejected — search indexes aren't durable systems of record).

The tradeoff I'm accepting deliberately: no cross-store transactions. A conversation referencing a
task cannot be updated atomically with that task — it would need a domain-event/reconciliation
design rather than a distributed transaction. Kafka now exists only for the Calendar/Notification
foundation; no Mongo/AI flow uses it. I would still need to define an accepted consistency window,
failed-event reconciliation, and a UI fallback for a stale reference before claiming this design is
implemented.

If I ever got to a point where a "life timeline" view needed frequent, low-latency joins across journals and finance/task data, that's the trigger to revisit this — either JSONB-in-Postgres or a dedicated read-model projection would be worth evaluating at that point.

Relevant ADRs: [ADR-009](../adr/ADR-009-use-mongodb-for-journals-and-ai-conversations.md) and
[ADR-035](../adr/ADR-035-encrypted-mongodb-profile-journals.md)
