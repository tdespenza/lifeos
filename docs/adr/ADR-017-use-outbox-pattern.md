# ADR-017: Use the transactional outbox pattern for reliable event publishing

## Context

Several LifeOS services — Task/Goal, Finance, Document Vault, Notification — write state changes to PostgreSQL and must also publish a domain event (e.g., `GoalCompleted`, `TransactionPosted`, `DocumentHashAnchored`) to Kafka/Pulsar so downstream consumers (Notification, Analytics, Blockchain Trust Ledger) can react. A service handler that commits a database transaction and then calls the broker client is performing two independent writes against two different systems with no shared transaction. Either write can fail independently of the other, and Java 25 virtual threads make it cheap to have many such handlers in flight concurrently, which only increases the number of windows in which this can go wrong. This is the classic dual-write problem, and it directly threatens correctness guarantees the Notification and Finance services depend on (idempotency keys and retry/backoff only help once an event has actually been published).

## Options Considered

1. **Dual writes (DB commit, then direct broker publish)** — simplest to write, no new infrastructure or tables. Rejected: if the process crashes or the broker call fails after the DB commit, the event is silently lost with no record that it was ever owed; if the broker call succeeds but the surrounding transaction later rolls back, a phantom event is published for state that never existed. Both are real correctness bugs, not edge cases, and they are invisible until a downstream service misbehaves.
2. **Transactional outbox (chosen)** — write the domain event as a row in an `outbox_events` table in the same PostgreSQL transaction as the business write, then a separate relay (poller or CDC) publishes it to Kafka/Pulsar and marks it published. Rejected nothing — this is the selected option.
3. **Debezium / CDC off the WAL** — tail the PostgreSQL write-ahead log directly and stream row changes to Kafka via Kafka Connect, skipping the explicit outbox table and app-side polling. Rejected as the default: it still needs an outbox-shaped table to get a clean, versioned event schema instead of raw row diffs, and it adds a CDC connector, its own operational surface (connector configs, log retention, replication slot lag monitoring), and another moving part every engineer on the team has to understand. Kept as a documented future upgrade path (see below).
4. **2PC / XA transactions between PostgreSQL and the broker** — theoretically gives atomic all-or-nothing commit across both systems. Rejected: Kafka has no native XA/2PC support, so this would require a broker-side compensating design anyway; even where XA is available (e.g., some JMS brokers), it serializes commits through a transaction coordinator, adds latency to every write path, and is a well-known source of blocking failure modes under partial outages — a poor fit for a system built around virtual threads and high concurrency.

## Decision Made

Use the transactional outbox pattern: every service that must publish a domain event writes that event, in the same PostgreSQL transaction as its business state change, to an `outbox_events` table (event id, aggregate id, aggregate version, type, topic, partition key, payload, headers, created_at, published_at). A separate relay process polls unpublished rows in commit order, publishes them to Kafka/Pulsar, and marks them published.

The immutable outbox event id is the CloudEvents `id` and consumer idempotency key. The Kafka record key is the stable partition key—normally the aggregate id, or the recipient account id for recipient-ordered notifications. Those keys have different jobs: using a unique event id as the Kafka key would distribute successive events from one aggregate across partitions and contradict the required per-aggregate ordering guarantee.

## Why

The outbox pattern converts an inherently distributed, non-atomic operation (write to Postgres + publish to Kafka) into a single atomic operation (write two rows to Postgres) plus a separately retriable, idempotent relay step. This gives us the correctness property that actually matters — an event exists if and only if the business transaction that produced it committed — without taking on 2PC's latency and blocking behavior or CDC's extra infrastructure on day one. It also composes cleanly with the idempotency-key and dead-letter-queue patterns already mandated for the Notification Service, since the outbox row's id is a natural, durable idempotency key while the aggregate-based partition key preserves ordering.

## Tradeoffs

- Extra write per business transaction (one more row, same transaction, same commit) — negligible latency cost, but it does add to the row count and I/O of every write path that emits events.
- The relay introduces publish lag: events are visible to consumers only after the next poll cycle, not the instant the transaction commits. This must be bounded and documented per service (target below), not left open-ended.
- The outbox table needs its own lifecycle management — published rows must be pruned or archived on a schedule, or the table becomes an unbounded append-only log that degrades poll query performance over time.
- A naive single-threaded poller becomes a throughput ceiling and a single point of publish delay; it must be designed for parallelism (partitioned polling by aggregate id, `SELECT ... FOR UPDATE SKIP LOCKED`) from the start, not bolted on later.
- Ordering is only guaranteed per aggregate (via the persisted aggregate partition key), not globally — consumers must not assume cross-aggregate event ordering. The relay must preserve aggregate-version order within that key; consumers still dedupe using the distinct immutable event id.

## Consequences

- Every service that publishes domain events must add an `outbox_events` table, a relay component (poller or, later, Debezium connector), and a pruning job — this is now a required part of the service template, not optional.
- Consumers (Notification, Analytics, Blockchain Trust Ledger) can rely on at-least-once delivery with a stable CloudEvents/event id, so they must implement idempotent handlers regardless of relay implementation details — this decouples consumer correctness from whichever relay mechanism a producer uses. Producers use the aggregate or recipient partition key as the Kafka record key; consumers must not substitute that ordering key for idempotency.
- We take on operational ownership of the relay (poll interval tuning, lag alerting, backlog handling during broker outages) rather than pushing that complexity onto the broker or a 2PC coordinator.
- Because the outbox table and relay contract are stable, migrating any individual service from a polling relay to Debezium/CDC later is an internal implementation swap, not an API or schema change for consumers.

## When This Decision Would Be Wrong

If event volume from a single service grows to the point where poll-based relay latency (see validation target below) can no longer keep up even after partitioning and tuning — realistically once a service is sustaining sub-second delivery SLAs at high write throughput (thousands of writes/sec) — the polling relay should be replaced with Debezium/CDC for that service, since CDC removes poll latency entirely by streaming from the WAL. Similarly, if the platform consolidates onto a single monolith or a single dominant datastore where cross-store atomicity is no longer required, the outbox pattern's justification (bridging two independently-committing systems) disappears and it should be removed rather than kept as unnecessary indirection.

## How We Will Validate It

- Load test: for each service using the outbox, run a sustained write load test (target: 500 writes/sec for Finance and Task/Goal, the highest-traffic transactional services) and measure outbox-to-Kafka publish lag at p50/p95/p99; target p99 relay lag under 2 seconds under load.
- Correctness test: inject a forced process crash between the DB commit and the relay's next poll cycle in a staging environment; verify zero event loss (every committed business transaction eventually produces exactly one published event) and zero phantom events (a rolled-back transaction produces no outbox row).
- Idempotency test: replay the same outbox event id twice into a consumer (Notification Service) and assert no duplicate side effect (no duplicate notification sent), validating the idempotency-key contract end to end.
- Operational metric: expose `outbox_unpublished_row_count` and `outbox_oldest_unpublished_age_seconds` per service via OpenTelemetry/Prometheus, with an alert threshold (age > 30s) — this is the concrete signal that would trigger the CDC migration discussed above, not a vague "watch it."
