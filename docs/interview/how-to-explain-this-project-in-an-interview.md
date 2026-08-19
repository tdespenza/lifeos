# How to Explain This Project in an Interview

If I get asked "tell me about a project you're proud of," here's roughly how I'd answer it in about a minute.

I'm building LifeOS — a personal-operating-system platform — and I'm treating it as a real FAANG-style engineering exercise, not just a portfolio CRUD app. The target vision is a single system spanning tasks, calendar, finance, documents, and AI assistance, built on Java 25 with a Spring Boot microservices backend, eventually fronted by Angular web, JavaFX/GraalVM desktop, and Flutter mobile clients. That's the destination — I say that clearly, because where it actually stands today is much smaller, and I'd rather be upfront about that than let it sound more finished than it is.

The current repository contains packageable gateway, identity, task-goal, profile, notification,
calendar, finance, and trust-ledger services. Gateway is the bounded public ingress for the
configured service prefixes; Identity supplies session validation and fail-closed V2 authorization;
Task/Goal has durable lifecycle and persisted graph ordering; Calendar/Notification share a
privacy-safe reminder-outbox path; Finance has immutable integer-minor-unit postings and bounded
forecasts; and Trust Ledger exposes stateless document/Merkle proof primitives. Those are tested
implementation slices, not evidence that every module is deployed together in production.

The Spring Boot modules run on the Java 25 virtual-thread model. PostgreSQL is the system of
record for the stateful domains, while Redis backs gateway/identity rate limits and short-lived
security state. Kafka has a deliberately narrow foundation: versioned event contracts, Calendar
and Notification outboxes/consumers, and a local Compose profile. Generated versioned gRPC
contracts also exist. What does *not* exist is a GraphQL application, live gRPC/mTLS transport,
production Kafka/provider operations, Besu/Web3j anchoring, AI orchestration, video streaming,
client apps, or Kubernetes deployment. I am explicit about those boundaries rather than implying
otherwise.

What I think is actually the interesting part for an interviewer is the reasoning trail behind where
it's going. The ADR set now includes the notification/event, persisted Task/Goal graph, bounded
Trust proof, Calendar reminder, and Finance posting/forecast decisions alongside the Java,
concurrency, PostgreSQL, and blockchain tradeoffs. So where a boundary is not implemented, its
tradeoff is traceable and dated rather than silently assumed.

So the pitch isn't "look at this finished platform." It's "here are several bounded, verified
service and contract foundations; here are the externally dependent pieces I have not claimed;
and here is the documented roadmap." I think that's more interview-relevant than a bigger pile of
undocumented code, because it shows how I make architecture decisions under real constraints, not
just that I can produce output.

## Relevant ADRs

- [ADR-001](../adr/ADR-001-use-java-25.md) — why Java 25
- [ADR-002](../adr/ADR-002-use-virtual-threads.md) — the concurrency model the current services already run on
- [ADR-005](../adr/ADR-005-use-spring-boot-microservices.md) — why microservices over a monolith for this project
- [ADR-008](../adr/ADR-008-use-postgresql-as-system-of-record.md) — why Postgres is the source of truth today
- [ADR-013](../adr/ADR-013-use-web3j-and-besu-for-blockchain.md) — the blockchain-proof design referenced as forward-looking
