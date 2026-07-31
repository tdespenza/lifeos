# How Does the System Handle Failure?

Right now, honestly, it mostly doesn't — and I want to be upfront about that before describing where it's headed. If PostgreSQL is unreachable when a request hits identity-service or task-goal-service, Spring Data JPA throws, it propagates up through the controller, and the client gets a 500. There's no retry, no circuit breaker, no fallback, no graceful degradation. That's the honest current state of a Phase 1 portfolio project with two services and no traffic to speak of, not a claim that this is production-hardened.

What does exist today, even if unglamorous, is failure *isolation* at the data layer: each service owns its own Postgres database (`lifeos_identity`, `lifeos_task_goal`), not a shared schema. That's a deliberate choice, not an accident — if task-goal-service's database gets slow, corrupted, or falls over, identity-service's data is untouched because there's no shared connection pool, no shared schema, no cross-service foreign key that could cascade damage. It doesn't buy me resilience in the request path yet, but it does mean the blast radius of a data-layer failure is contained to one service, which is the precondition for building real resilience on top later.

The plan for what comes next is where the interesting engineering is. Once there's an actual event bus (Kafka/Pulsar), every service that needs to publish a domain event — a goal completed, a document hash anchored — will write that event to an `outbox_events` row in the same Postgres transaction as the business write, instead of doing a direct dual-write to the broker. That closes the classic gap where the DB commits but the broker call fails (or vice versa) and you silently lose or duplicate an event. A separate relay polls unpublished rows and publishes them, so publishing failure becomes a retriable, idempotent problem instead of a correctness bug baked into the request path.

Layered on top of that: bounded retries with exponential backoff and jitter for transient failures, circuit breakers around outbound calls (starting once services actually call each other — right now they don't), and timeouts everywhere a request crosses a process boundary. None of that is code yet. I'd rather say clearly "this is the target design, here's why, and here's what's not built" than imply resilience patterns exist in two single-table CRUD services that have never seen a concurrent caller.

If I were asked in an interview "what happens when Postgres goes down right now," the honest answer is "the request fails loudly with a 500, and that's a known gap I'd close next if this were headed toward real traffic" — not a made-up uptime number.

## Relevant ADRs

- [ADR-017](../adr/ADR-017-use-outbox-pattern.md) — the outbox pattern, planned, for closing the dual-write gap once the event bus exists
- [ADR-008](../adr/ADR-008-use-postgresql-as-system-of-record.md) — per-service PostgreSQL and the failure-isolation reasoning behind it
- [ADR-016](../adr/ADR-016-use-event-driven-architecture.md) — the event-driven architecture the outbox pattern sits inside
