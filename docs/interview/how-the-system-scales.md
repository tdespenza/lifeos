# How Does This System Scale?

Let me be upfront about what "scale" means here today: nothing has been load-tested, there's no production traffic, and I have exactly two services running — identity-service (authentication, durable session validation, and authorization decisions) and task-goal-service (authenticated owner/tenant-scoped goal operations plus a topological-sort endpoint). So what I can actually talk about is design intent and the reasoning behind it, not measured throughput. If an interviewer pushes on numbers, the honest answer is "I haven't run that benchmark yet," not a made-up p99.

The design has a few scaling levers baked in from day one, even though most aren't under any real load yet.

First, each service owns its own PostgreSQL database — lifeos_identity and lifeos_task_goal are already physically separate, no shared schema, no cross-service joins. That's not just clean boundaries, it's a scaling decision: each service's write ceiling is that service's problem, not a shared bottleneck for the whole platform. It also means I can scale, tune, or even swap the storage for one service without touching another. The real tradeoff — no cross-service SQL joins, so composite reads have to be built via API composition later — is spelled out in ADR-008, along with the concrete threshold (roughly 5,000-10,000 TPS on a well-tuned primary) where I'd revisit Postgres for a given service.

Second, virtual threads are on (`spring.threads.virtual.enabled=true`) in both services today, which matters for I/O-bound concurrency — request-handling threads unmount while blocked on a DB call instead of parking an OS thread. That said, I want to be precise: neither service does any real concurrent fan-out yet, so I haven't actually exercised virtual threads under contention — there's no benchmark proving they help right now, just the JDK's own reasoning and ADR-002's rationale for why this shape of workload (blocking I/O, not CPU-bound) benefits from the model once fan-out shows up, like a future dashboard aggregating tasks, calendar, and finance concurrently.

Third, the planned direction for decoupling services under load is event-driven, not synchronous chains — Kafka as the backbone (ADR-016), so a spike in, say, notification volume doesn't propagate backpressure into the task service that produced the event. That's not built yet either.

Caching (Redis, running in docker-compose but wired into zero service code) is the next lever once there's an actual read-heavy path worth protecting.

The honest scaling story right now is: sound per-service boundaries, a concurrency model chosen for the right reasons, and nothing yet forcing me to prove it under load.

Relevant ADRs: [ADR-002](../adr/ADR-002-use-virtual-threads.md), [ADR-008](../adr/ADR-008-use-postgresql-as-system-of-record.md), [ADR-016](../adr/ADR-016-use-event-driven-architecture.md)
