# Why an Event Bus (Kafka/Pulsar) for Domain Events

There is a narrow Kafka foundation in the code now: `contracts:event-contracts` defines versioned
CloudEvents; Calendar writes privacy-safe `NotificationRequestedV2` records to a transactional
outbox; Notification consumes V1/V2 with durable inbox dedupe and emits delivery-status outbox
records; and the local Compose `eventing` profile supplies a single plaintext broker and explicit
development topics. That is not a production Kafka claim: no TLS/ACL/replication/retention
operations, provider delivery deployment, or broader domain-event coverage has been proven.

The reason it's in the plan at all comes from how the target architecture is decomposed. LifeOS now has twelve independently packageable Spring Boot service modules, and a bunch of the workflows across them are naturally asynchronous and cross-service: a task completion should eventually update analytics and maybe fire a notification; a document upload should trigger hashing and blockchain anchoring; a budget threshold being crossed should trigger a notification. None of those need a synchronous response, and none of them should block the request that triggered them. If I wired those up as direct REST or gRPC calls from the producing service, I'd get temporal coupling — task-goal-service would need to know about and call notification-service, analytics-service, and whatever else consumes that event, and if any of those is down or slow, the caller feels it. An event bus decouples that: the producer publishes once, and consumers come and go independently. I could add a fraud-detection consumer on document uploads later without touching the vault service that produces the event.

Kafka is the specific choice over Pulsar mainly because of ecosystem fit for patterns I already know I want — the transactional outbox pattern (so a DB write and an event publish don't become a dual-write consistency bug) leans on Debezium CDC, which is Kafka-native, and schema evolution tooling around Kafka is more mature. Pulsar's multi-tenancy and geo-replication are genuinely nice, but they solve a problem I don't have — this is a single-tenant personal platform, not multi-tenant SaaS. If that ever changed, I'd revisit it.

The honest caveat: introducing a broker means real operational surface I'd need to apply
conditionally, not blanket-apply everywhere. The Calendar/Notification path demonstrates the
outbox rule: a local state change and an outbox row commit together, then a relay publishes
at-least-once, so consumers still dedupe an uncertain retry. Not every producer needs an outbox if
it does not mutate durable state in the same operation. Saga orchestration is for workflows that
genuinely span independently committed services, not a default for every multi-step process. A
dead-letter topic earns its keep once retries cannot recover a failed message, with lag/DLQ-depth
monitoring focused on correctness-critical consumer groups. Production broker controls and broader
fanout remain work to build, not assumptions hidden by the existing foundation.

Relevant ADRs: [ADR-016](../adr/ADR-016-use-event-driven-architecture.md)
