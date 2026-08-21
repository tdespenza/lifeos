# ADR-016: Use Kafka/Pulsar for asynchronous domain events

## Context

LifeOS is decomposed into eleven Spring Boot microservices (identity, profile, task/goal, calendar, finance, document vault, media streaming, AI orchestrator, algorithm engine, blockchain trust ledger, notification, analytics). Several workflows are inherently cross-service and asynchronous: `TaskCompletedEvent` fans out to analytics and notification; `DocumentUploadedEvent` triggers hashing and blockchain anchoring in the trust ledger; `VideoSessionEndedEvent` triggers analytics rollups and storage lifecycle actions; `BudgetThresholdExceededEvent` triggers notification; `BlockchainProofAnchoredEvent` triggers a vault-record update. None of these require a synchronous response, all can tolerate sub-second-to-seconds delay, and new consumers (e.g., a future fraud-detection service on document uploads) should be addable without redeploying producers. The project also exists to demonstrate distributed-systems judgment in a portfolio context, which favors patterns (log-based replay, consumer groups, outbox/CDC) that show up in senior/staff system-design interviews.

## Options Considered

- **RabbitMQ (AMQP broker).** Simple to operate, excellent for task-queue and RPC-style routing. Rejected as the default because it lacks native offset-based replay and long retention — reprocessing history for a new consumer requires bolting on extra infrastructure, which works against the log-as-source-of-truth pattern this project wants to showcase.
- **Synchronous-only service-to-service calls (REST/gRPC), no broker.** Simplest possible architecture, zero extra infra. Rejected because it creates temporal coupling (task-service must call notification, analytics, etc. inline), has no durability if a downstream service is down, and eliminates outbox/saga/DLQ patterns entirely — an explicit non-goal.
- **Cloud-managed event bus (e.g., Confluent Cloud, AWS MSK).** Removes operational burden. Rejected as the default because it's a recurring paid external dependency, conflicts with the self-hosted portfolio-lab constraint, and removes the hands-on operational learning (partitioning, ISR, consumer-lag tuning) that is part of the demonstration value.
- **Apache Pulsar (the sibling option in REQUIREMENTS.md).** A genuine finalist — segment-based storage, native multi-tenancy, tiered storage. Not chosen as default; see below.

## Decision Made

Kafka is the default event backbone for all listed domain events. Pulsar remains an acceptable substitute noted in requirements, but Kafka is what services, CI, and local dev-compose target.

Versioned event payloads are maintained in the shared `contracts:event-contracts` module. Each
CloudEvents type and Kafka topic carries an explicit schema version; breaking payload changes
introduce a new version rather than reinterpreting an existing record. Event envelopes carry only
bounded routing/correlation metadata, while sensitive delivery targets and document contents stay
behind the owning service boundary.

## Why

Kafka wins on ecosystem maturity for the specific patterns this project commits to: the transactional outbox pattern leans on Debezium CDC, which is Kafka-native and has the deepest reference material; schema evolution is well-served by Confluent/Apicurio Schema Registry; and operational tooling (kcat, Kafka UI, Cruise Control) is more battle-tested than Pulsar's equivalents. Kafka is also the broker interviewers and reviewers are most likely to probe deeply, so demonstrating fluency there has higher portfolio payoff than Pulsar's less commonly interviewed operational model (BookKeeper + a metadata store). Pulsar's multi-tenancy and geo-replication are compelling but solve a problem LifeOS doesn't have yet — a single-tenant personal platform, not a multi-tenant SaaS.

## Tradeoffs

Kafka requires deliberate partition-key design (e.g., `documentId`/`userId` as key) to get per-entity ordering, and offers no built-in multi-tenant namespacing — topic naming and ACL discipline substitute for it across eleven producers. Compared to RabbitMQ, there are no priority queues or exchange-style routing topologies; fan-out is expressed through topic/partition conventions instead. Running Kafka correctly (KRaft mode, replication factor, min-insync-replicas) is nontrivial extra operational surface versus a single RabbitMQ node.

## Consequences

Every producer must adopt the transactional outbox pattern (local DB write + event publish in one transaction, relayed via Debezium or a polling publisher) to avoid dual-write inconsistency. Multi-step workflows like document-upload-to-anchor require saga orchestration (choreography or an explicit orchestrator) rather than a single transaction. Every critical consumer group needs a dead-letter topic with alerting on DLQ depth. Consumer lag, per-topic throughput, and DLQ depth become first-class OpenTelemetry/Prometheus metrics. A production-realistic demo requires a 3-broker cluster, adding backup/restore runbooks and disk-capacity planning to the infra footprint.

## When This Decision Would Be Wrong

If LifeOS were scoped down to a single-user, single-deployable app with no cross-service fan-out, or if the deployment target shrank to a single free-tier VM where a 3-broker cluster is infeasible, RabbitMQ's single-node simplicity would be the better call — revisit at that point. Conversely, if LifeOS pivots toward multi-tenant SaaS with per-tenant data-residency and geo-replication as hard requirements, Pulsar's native multi-tenancy would outweigh Kafka's ecosystem advantage and should be reconsidered then.

## How We Will Validate It

Run a load test against a 3-broker local cluster targeting 1,000 events/sec sustained across the five event types, with acceptance criteria: p99 consumer lag under 5 seconds, p99 producer-to-consumer latency under 500ms, and DLQ rate under 0.1%. Run a chaos test that kills one broker mid-load-test with `acks=all` and `min.insync.replicas=2`, confirming zero message loss and consumer-group rebalance completing within 30 seconds. Separately, verify consumer idempotency by replaying a captured event batch twice and asserting no duplicate side effects (e.g., no duplicate notifications or double-anchored proofs).
