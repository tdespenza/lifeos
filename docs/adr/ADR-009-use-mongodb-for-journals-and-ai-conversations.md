# ADR-009: Use MongoDB for journals, notes, and AI conversation history

## Context

LifeOS already uses PostgreSQL as the system of record for structured entities: identity, profile, tasks/goals, calendar events, finance transactions, and document-vault metadata. These entities have stable, well-understood schemas and benefit from relational integrity, joins, and transactional guarantees.

A separate class of content does not fit that model well: personal journals, free-form notes, and AI conversation history from the AI orchestrator service. These share three properties:

- **Schema volatility** — journal entries acquire new optional fields over time (mood tags, location, attachments, linked entities); AI conversation turns carry variable structure per model/provider (tool calls, function results, multi-modal blocks, streaming deltas, token usage metadata) that changes as we add providers or capabilities.
- **Deep nesting** — a single AI conversation is a tree of turns, each with nested tool-call arguments, retrieved-context snippets, and provider-specific metadata. Modeling this relationally would require many join tables or a single wide JSON blob with no query ergonomics.
- **Read/write pattern favors whole-document access** — the primary access pattern is "fetch this conversation" or "fetch this journal entry," not cross-entity relational joins with tasks or finance records.

The decision below is already fixed in REQUIREMENTS.md; this ADR documents the reasoning so the tradeoff is auditable and revisitable.

## Options Considered

1. **MongoDB as a secondary datastore alongside PostgreSQL** (chosen) — a purpose-built document database for this specific content class, with PostgreSQL remaining the system of record for structured entities.
2. **JSONB columns in PostgreSQL** — store journals/notes/AI conversations as `jsonb` columns in existing Postgres tables. Rejected as the primary approach because Postgres's JSONB query and indexing ergonomics (GIN indexes, `jsonb_path_query`) are workable for shallow documents but become awkward for deeply nested, frequently mutated structures like multi-turn AI conversations with nested tool calls — every schema evolution in the AI turn structure requires reasoning about partial-update semantics and index maintenance that MongoDB's document model handles natively. It also avoids a second database technology, which is a real and legitimate cost we are consciously accepting to trade away.
3. **Cassandra (or another wide-column store)** — rejected because it optimizes for massive write-throughput and multi-datacenter linear scalability that LifeOS does not need at its target scale (single-user-per-tenant personal data, not planet-scale event ingestion). Cassandra's eventual-consistency model and lack of ad-hoc query flexibility (no secondary indexes without extra tooling, no native aggregation pipeline) would add operational complexity — tuning consistency levels, managing compaction strategy, running a multi-node cluster — without a corresponding benefit for this workload.
4. **Elasticsearch/OpenSearch as primary store** — briefly considered since journals/notes benefit from full-text search, but rejected as a system of record because search indexes are not designed as durable primary stores (no ACID guarantees, awkward partial updates); it remains a candidate as a downstream search index fed from MongoDB via change streams, not a replacement for this decision.

## Decision Made

Use MongoDB as the document store for journals, flexible notes, and AI conversation history, operating alongside PostgreSQL (system of record for structured entities), Redis (cache/session), and a vector DB (embeddings for AI retrieval). Each microservice owns its own data; only the profile/journal service and the AI orchestrator service talk to MongoDB directly.

## Why

MongoDB's document model matches the actual shape of this data: a conversation or journal entry is naturally a single nested JSON document, not a normalized set of rows. Schema-on-read means adding a new field to AI turn metadata (e.g., a new provider's token-usage shape) requires no migration — existing documents remain valid, new documents carry the new field. The aggregation pipeline gives us native support for querying nested arrays (e.g., "find conversations where any tool call failed") without the join complexity JSONB would impose. Horizontal scaling via sharding is available if a single user's conversation history or the aggregate journal corpus grows large, without re-architecting the service.

## Tradeoffs

- **Operational cost**: a second stateful database technology means a second backup/restore procedure, a second set of monitoring dashboards (replica set health, oplog lag, WiredTiger cache pressure), and a second skill requirement for on-call engineers. This is the most concrete cost of choosing option 1 over option 2.
- **No cross-store transactions**: an AI conversation referencing a task (stored in Postgres) cannot be updated atomically with that task. We accept eventual consistency here, reconciled via domain events on Kafka (e.g., `ConversationLinkedToTask`), not distributed transactions.
- **Query flexibility vs. relational joins**: MongoDB's `$lookup` aggregation stage can approximate joins but is materially weaker than Postgres joins for multi-entity queries (e.g., "all journal entries mentioning a given finance transaction"). We mitigate this by keeping cross-domain references as opaque IDs resolved at the application layer, not by querying across stores.
- **Consistency model**: MongoDB's default read/write concerns (`majority` write concern, `local`/`majority` read concern) trade some latency for durability; we are choosing durability given this is personal, non-recoverable user content (a lost journal entry or conversation cannot be reconstructed, unlike a cache miss).

## Consequences

- The AI orchestrator and journal/notes services each get a MongoDB client and connection pool sized independently of the Postgres pool, requiring separate connection-limit and timeout tuning in each service's resource budget.
- Document schemas are enforced at the application layer (via Java records/DTOs and optional MongoDB JSON Schema validators on the collection), not the database layer — this pushes correctness responsibility onto code review and tests rather than DDL constraints, and must be explicitly compensated for with contract tests on document shape.
- Backup/restore and disaster-recovery runbooks must cover two independent storage engines with different point-in-time-recovery mechanics (Postgres WAL vs. MongoDB oplog), which the platform/observability team must document and rehearse separately.
- Data export (e.g., GDPR-style user data export) must join across Postgres and MongoDB in application code, since there is no single-query cross-store export path.

## When This Decision Would Be Wrong

This decision should be revisited if either of the following occurs:

1. **AI conversation and journal schemas stabilize** to the point where they're effectively fixed (e.g., we settle on one AI provider with one stable turn format, and journal entries stop acquiring new optional fields). At that point, JSONB in Postgres becomes viable and the operational cost of a second database stops being justified by schema flexibility we no longer need.
2. **Cross-store query patterns become the norm rather than the exception** — for example, if a major feature requires frequent, latency-sensitive joins between journal content and finance/task data (e.g., a unified "life timeline" view aggregating all domains with sub-100ms query targets). At that point, the cost of application-layer joins across two stores could outweigh MongoDB's document-modeling benefit, and consolidating onto Postgres (with JSONB for the flexible parts) or introducing a dedicated read-model/CQRS projection would be worth evaluating.

A team-size or budget contraction that makes maintaining a second stateful datastore operationally unsustainable would also be a trigger to consolidate onto Postgres/JSONB, accepting reduced query ergonomics in exchange for one less system to operate.

## How We Will Validate It

- **Write-path benchmark**: load-test the AI orchestrator's conversation-persistence path with realistic conversation shapes (10–50 turns, nested tool calls, ~5–20KB per document) at a target of p99 write latency under 50ms and p99 read latency under 20ms for single-document fetch, under a simulated concurrent load of 200 in-flight conversations.
- **Schema evolution test**: as a concrete regression test, add a new field to the AI turn document shape (simulating a new provider integration) and verify zero-downtime deployment with no migration step required, versus timing the equivalent change against a JSONB-in-Postgres prototype to confirm the qualitative ergonomics claim with a quantitative diff (lines of migration/validation code required).
- **Aggregation query benchmark**: run representative aggregation queries (e.g., "conversations with failed tool calls in the last 30 days," "journal entries by tag with word-count aggregation") against a seeded dataset of 1M documents and confirm p95 latency stays under 200ms without requiring additional indexes beyond what's documented in the service's data-access layer.
- **Operational readiness check**: before this goes to production, confirm a documented and tested restore-from-backup runbook for the MongoDB replica set achieves an RPO under 5 minutes and RTO under 30 minutes, matching the reliability bar already established for the Postgres cluster.
- **Ongoing signal**: track MongoDB-specific metrics (replica set lag, WiredTiger cache eviction rate, connection pool saturation) in Grafana alongside existing Postgres dashboards, with alerting thresholds set before the service carries production user data — not monitored ad hoc after the fact.
