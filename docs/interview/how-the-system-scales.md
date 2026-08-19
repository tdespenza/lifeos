# How Does This System Scale?

Let me be upfront about what "scale" means here today: nothing has been load-tested and there is
no production traffic. The repository has Gateway, Identity, Task/Goal, Profile, Notification,
Calendar, Finance, and Trust Ledger modules, but that is not evidence of fleet-wide capacity. What
I can talk about is bounded design and module verification, not measured throughput. If an
interviewer pushes on numbers, the honest answer is "I haven't run that benchmark yet," not a
made-up p99.

The design has a few scaling levers baked in from day one, even though most aren't under any real load yet.

First, each stateful service owns its PostgreSQL database — `lifeos_identity`,
`lifeos_task_goal`, `lifeos_profile`, `lifeos_notification`, `lifeos_calendar`, and
`lifeos_finance` are separate, with no shared schema or cross-service joins. That's not just clean
boundaries: each service's write ceiling is its own problem, not a shared bottleneck for the whole
platform. The tradeoff is composite reads require API composition or a read model; no benchmark has
established a TPS threshold for changing that design.

Second, virtual threads are on (`spring.threads.virtual.enabled=true`) in the current Spring Boot
modules, which matters for I/O-bound concurrency. That said, no product fan-out or large-load
scenario has been exercised, so there is no benchmark proving a throughput win. A future dashboard
aggregating tasks, calendar, and finance is a candidate for measured structured-concurrency fan-out.

Third, the eventing foundation is now real but narrow: Calendar writes privacy-safe
`NotificationRequestedV2` records through a transactional outbox, and Notification durably
consumes/fans out outcomes through its own outbox. The local Compose eventing profile supplies a
single development Kafka broker. That does not yet establish production Kafka capacity, ACL/TLS,
retention operations, or eventing for every domain.

Redis already enforces gateway and identity rate limits and stores short-lived OIDC/WebAuthn state;
it is not yet used as a general read cache. A domain read-cache policy is the next lever once there
is an actual read-heavy path worth protecting.

The honest scaling story right now is: sound per-service boundaries, a concurrency model chosen for the right reasons, and nothing yet forcing me to prove it under load.

Relevant ADRs: [ADR-002](../adr/ADR-002-use-virtual-threads.md), [ADR-008](../adr/ADR-008-use-postgresql-as-system-of-record.md), [ADR-016](../adr/ADR-016-use-event-driven-architecture.md)
