# How to Explain This Project in an Interview

If I get asked "tell me about a project you're proud of," here's roughly how I'd answer it in about a minute.

I'm building LifeOS — a personal-operating-system platform — and I'm treating it as a real FAANG-style engineering exercise, not just a portfolio CRUD app. The target vision is a single system spanning tasks, calendar, finance, documents, and AI assistance, built on Java 25 with a Spring Boot microservices backend, eventually fronted by Angular web, JavaFX/GraalVM desktop, and Flutter mobile clients. That's the destination — I say that clearly, because where it actually stands today is much smaller, and I'd rather be upfront about that than let it sound more finished than it is.

Right now, two services are running. identity-service handles account registration, login/session validation, and deterministic RBAC/ABAC decisions. task-goal-service exposes authenticated owner/tenant-scoped goal create, list, and read operations, plus a dependency-ordering endpoint that runs Kahn's algorithm over a supplied goal dependency graph and returns an HTTP 409 for a cycle rather than silently returning a broken order. That gives the project both a real graph algorithm and a fail-closed, object-level authorization boundary rather than a toy CRUD surface.

Both services run on the Java 25 virtual-threads model, with Postgres as the system of record per service and Redis sitting in docker-compose for when I need it. Everything else in the target architecture — GraphQL, gRPC, Kafka, the AI orchestrator, blockchain document-integrity proofs, video streaming, the client apps, Kubernetes — isn't built yet. I'm explicit about that rather than implying otherwise.

What I think is actually the interesting part for an interviewer is the reasoning trail behind where it's going. I've already written 18 ADRs — why Java 25 over the alternatives, why virtual threads plus structured concurrency plus scoped values as the concurrency model, why Postgres as the system of record with Mongo and a vector database layered in later, why Web3j and Hyperledger Besu for blockchain proofs that hash documents rather than ever put private data on-chain. So even where the code doesn't exist yet, the tradeoff analysis does, and it's traceable and dated.

So the pitch isn't "look at this finished platform." It's "here's a small amount of code built correctly and deliberately, here's the documented reasoning behind a much larger system I'm building toward, and here's the phased roadmap that gets me there." I think that's more interview-relevant than a bigger pile of undocumented code, because it shows how I make architecture decisions under real constraints, not just that I can produce output.

## Relevant ADRs

- [ADR-001](../adr/ADR-001-use-java-25.md) — why Java 25
- [ADR-002](../adr/ADR-002-use-virtual-threads.md) — the concurrency model the current services already run on
- [ADR-005](../adr/ADR-005-use-spring-boot-microservices.md) — why microservices over a monolith for this project
- [ADR-008](../adr/ADR-008-use-postgresql-as-system-of-record.md) — why Postgres is the source of truth today
- [ADR-013](../adr/ADR-013-use-web3j-and-besu-for-blockchain.md) — the blockchain-proof design referenced as forward-looking
