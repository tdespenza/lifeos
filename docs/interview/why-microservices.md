# Why Microservices Instead of a Modular Monolith?

Honest answer first: the current repository has twelve independently packageable gateway, identity,
task-goal, profile, notification, calendar, finance, trust-ledger, document-vault, media,
AI-assistant, and analytics services—not the entire target platform. It also has shared Algorithm,
CloudEvents/Kafka, Trust, and generated gRPC contract foundations. That is still not a production
deployment claim, and it leaves important external/provider workflows unfinished. The distinction
matters in an interview: the architecture is no longer merely aspirational, but it is not safe to
present it as a finished fleet either.

I chose microservices over a modular monolith because the target domains genuinely don't share a failure or scaling profile. Finance and identity need strict transactional integrity, audit trails, and tight change control. Media streaming and AI orchestration are bursty and compute-heavy — they want to scale independently and spike without dragging everything else up with them. The blockchain trust ledger only anchors hashes; it can lag behind the rest of the system for minutes without any correctness problem. A modular monolith with clean internal boundaries would have been the "simpler, more honest for a one-person project" choice, and I considered it seriously — it avoids distributed transactions, N deployment pipelines, and cross-service network calls entirely. I rejected it as the primary architecture because those boundaries are also compliance and blast-radius boundaries, not just code-organization boundaries: a monolith means a bug or resource exhaustion in Notification runs in the same process as Finance, sharing the same memory and failure domain, so it can take Finance down with it. A separate deployable can reduce that blast radius, though it's not automatic — shared infrastructure underneath (a shared database, shared ingress, shared service discovery) can still propagate a failure across service boundaries even when the code itself is separated, so the isolation is only as real as the infrastructure decisions that back it.

The other honest factor: this project doubles as a portfolio piece for FAANG-style interviews, and a modular monolith doesn't let me demonstrate service discovery, distributed tracing, independent deployability, or circuit-breaker patterns — skills the target roles specifically probe for. I'd rather show that judgment now than bolt it on later.

What makes this affordable at all is Java 25 virtual threads (enabled in the current Spring Boot
modules via `spring.threads.virtual.enabled=true`, though no product fan-out path has been
load-tested). The classic microservices tax — a thread blocked per outstanding network call — stops
being the throughput penalty it used to be, which is what tips the cost-benefit calculation toward
"many services" over "one."

Where I'd be wrong: if this stayed a single-maintainer project with no traffic or team growth, the operational cost of a dozen pipelines and dashboards would outweigh the benefit, and I'd collapse low-traffic, tightly-coupled services like Profile, Notification, and Analytics into a coarser set of three or four instead of twelve.

**Relevant ADRs:** [ADR-005](../adr/ADR-005-use-spring-boot-microservices.md)
