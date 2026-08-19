# How Does the System Handle Failure?

The gateway path has real resilience now: explicit inbound/outbound timeouts, safe-read retries,
per-route bulkheads/circuit breakers, and fail-closed Redis rate limiting. Calendar and Notification
also have bounded outbox/lease/retry/dead-letter paths. That does not make the whole platform
production-hardened. If a required PostgreSQL database is unreachable for a stateful service, that
domain operation can still fail rather than silently fabricate a fallback. There is no production
traffic or deployed SRE control plane behind these mechanisms.

What does exist today, even if unglamorous, is failure *isolation* at the data layer: stateful
services own `lifeos_identity`, `lifeos_task_goal`, `lifeos_profile`, `lifeos_notification`,
`lifeos_calendar`, or `lifeos_finance`, not a shared schema. If one database gets slow, corrupted,
or falls over, another service's data is untouched because there is no shared connection pool,
schema, or cross-service foreign key. It does not buy full request-path resilience, but it contains
the data-layer blast radius.

The eventing foundation already demonstrates this boundary for Calendar reminders and Notification
delivery status: a local state change and outbox row commit together, a relay publishes at least
once, and Notification dedupes by CloudEvents ID. If a relay does not observe a broker
acknowledgement, it can republish; that is a duplicate, not an exactly-once result. Consumers must
still be idempotent. Applying this pattern to future producers such as Task/Goal or anchor workflows
is remaining work, not an implication of the current Calendar/Notification path.

The next resilience work is to apply equivalent dependency isolation to every future external boundary, especially asynchronous consumers and new domain-service calls. Gateway already has bounded safe-read retries, circuit breakers, bulkheads, and timeouts; task-goal and identity have bounded internal HTTP clients but do not yet have the gateway's full circuit/bulkhead policy. I'd rather say clearly which mechanisms are implemented and which remain target design than imply a uniform platform policy before it exists.

If I were asked in an interview "what happens when Postgres goes down right now," the honest answer is "the request fails loudly with a 500, and that's a known gap I'd close next if this were headed toward real traffic" — not a made-up uptime number.

## Relevant ADRs

- [ADR-017](../adr/ADR-017-use-outbox-pattern.md) — the outbox pattern implemented for the narrow Calendar/Notification path and planned for future publishers
- [ADR-008](../adr/ADR-008-use-postgresql-as-system-of-record.md) — per-service PostgreSQL and the failure-isolation reasoning behind it
- [ADR-016](../adr/ADR-016-use-event-driven-architecture.md) — the event-driven architecture the outbox pattern sits inside
