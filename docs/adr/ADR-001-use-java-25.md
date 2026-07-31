# ADR-001: Use Java 25 as the primary backend, algorithms, and tooling language

## Context

LifeOS is a polyglot-adjacent but JVM-centric system: eleven Spring Boot microservices, an algorithm engine, an AI orchestrator, and a blockchain integration layer, all exposed over REST/GraphQL/gRPC and communicating through Kafka/Pulsar. The project has two goals that must both be satisfied by the language choice: (1) run a real, concurrent, I/O-heavy distributed system efficiently, and (2) serve as a FAANG-caliber portfolio piece that demonstrates depth in algorithms, concurrency, and JVM internals — not just framework usage. The team is small (effectively solo-to-small), so language ecosystem maturity, tooling, and hiring signal matter as much as raw runtime capability.

## Options Considered

- **Kotlin (JVM)** — more concise syntax, first-class null safety, full interop with Spring/JVM libraries. Rejected as primary because it dilutes the "senior Java engineer" portfolio signal the project is explicitly optimizing for, and adds a second language surface (Kotlin idioms, coroutines vs. structured concurrency) without a corresponding capability the JVM doesn't already provide via Java 25.
- **Go** — excellent goroutine-based concurrency and small, fast binaries, well suited to infrastructure-style services. Rejected because its generics, type system, and standard library are weaker fits for the algorithm-engine and AI-orchestration work (graph algorithms, DP, sealed-type domain modeling) that is central to the interview-signal goal, and it fragments the stack away from Spring's ecosystem maturity.
- **Node.js/TypeScript** — fastest iteration for a web-facing team, huge package ecosystem. Rejected for backend/algorithm work: single-threaded event-loop concurrency doesn't demonstrate JVM-level concurrency/memory-model depth, and the type system is weaker for encoding invariants (sealed hierarchies, exhaustive pattern matching) that the algorithm engine relies on for correctness.
- **Java 21 LTS (stay on prior LTS)** — safer, more battle-tested ecosystem. Rejected as primary because Scoped Values (JEP 506) only reach finalized, non-preview status in Java 25 — on 21 they're still preview and changed shape across 21–24. Structured Concurrency (JEP 505) is *not* finalized in Java 25 either — it remains a fifth-iteration preview API requiring `--enable-preview` — but 25 is still the newest LTS to build on, and virtual threads' pinning behavior also improved materially between 21 and 25 (JEP 491, Java 24, removed `synchronized`-block pinning as the dominant cause). Building the orchestrator and algorithm engine's concurrency model on a still-preview structured-concurrency API is a real, accepted risk either way; Java 25 was chosen for the APIs that *do* reach finalized status plus the pinning fix, not because everything reached GA simultaneously.

## Decision Made

Use Java 25 as the default language for all backend microservices, the algorithm engine, AI orchestration, blockchain integration, CLI tooling, shared domain libraries, and the JavaFX desktop client (via GraalVM Native Image), wherever the JVM is a viable runtime target.

## Why

Java 25 is the first LTS where virtual threads (finalized since Java 21) and scoped values (JEP 506, finalized in 25) are both stable, non-preview APIs — precisely the foundation the microservice fan-out (orchestrator calling multiple AI providers, algorithm engine parallelizing work) needs without adopting a reactive programming model. Structured concurrency (JEP 505) is *not* finalized in Java 25 — it remains a preview API (fifth iteration, `--enable-preview` required) — so any code using `StructuredTaskScope` on this LTS is deliberately built on a still-moving API, accepted as a documented risk (see Tradeoffs) rather than glossed over as already-stable. Records, sealed interfaces, pattern matching, and switch expressions let the algorithm engine and domain layer encode invariants at compile time — directly useful for the "senior engineering judgment" goal, since exhaustive pattern matching over sealed hierarchies is exactly the kind of code that reads well in a design review. JFR and JVM tuning give free, production-grade observability and profiling without extra agents, reinforcing the observability pillar of the project.

## Tradeoffs

- Java 25 shipped recently (GA September 16, 2025), so third-party library and tooling compatibility (Web3j/Besu clients, GraalVM Native Image support for the newest class file version, container base images, APM/OTel agent instrumentation) is less proven than on Java 17/21 — expect to pin specific compatible versions and validate each dependency explicitly rather than assuming compatibility.
- Virtual threads are not a free performance win: as of JEP 491 (Java 24, carried into 25), ordinary `synchronized` blocks no longer pin the carrier thread, but native calls (JNI, Foreign Function & Memory API) and class initialization/resolution still do, and thread-local-heavy libraries (older JDBC drivers, some connection pools) can still degrade throughput under load through other mechanisms. Every blocking dependency (HikariCP, JDBC drivers, Kafka client) must be verified as virtual-thread-safe, not assumed.
- Structured Concurrency (JEP 505) is a preview API in Java 25, not finalized — code using `StructuredTaskScope` needs `--enable-preview` on both compilation and the running JVM, and the API has changed shape in every JEP iteration so far (428→437→453→462→480→499→505). This is a real, accepted risk for any service that adopts it before it finalizes, not a footnote — see ADR-003 for how this risk is isolated.
- GraalVM Native Image for the JavaFX desktop client adds real build complexity (reflection/resource config, longer CI times) and its own version-compatibility dependency on Java 25 bytecode support.
- Fewer engineers currently have production experience with Java 25-specific concurrency idioms than with Java 17/21, which raises onboarding cost if the team grows.

## Consequences

- Most services use Spring MVC with the virtual-thread executor rather than WebFlux, since virtual threads remove most of the motivation for reactive code; WebFlux/true async is reserved for the WebRTC/HLS media-streaming gateway where a genuine async event loop is still warranted.
- CI/CD must pin a GraalVM distribution matching Java 25 for the desktop Native Image build, and base container images must be validated against Java 25 (not just the latest generic "LTS" tag).
- JFR becomes the default profiling mechanism, wired into each service with recordings shipped to the observability stack (Grafana/Loki) as a first-class signal, not an ad hoc debugging tool.
- The algorithm engine is modeled with records/sealed interfaces/pattern matching, which becomes reusable interview-portfolio material and a template other services follow for domain modeling.

## When This Decision Would Be Wrong

If the team grows to include engineers whose primary strength is not the JVM (e.g., a majority frontend/Node background) and delivery velocity becomes more important than demonstrating JVM concurrency depth, Kotlin (for conciseness) or offloading specific compute-heavy services to Go should be reconsidered. Separately, if production experience surfaces unresolved virtual-thread pinning or scheduler-starvation issues at the orchestrator's target concurrency (e.g., sustained fan-out beyond roughly 50–100k in-flight virtual threads per node for AI provider calls) that aren't resolved within the next one to two LTS cycles, the AI orchestrator specifically should be revisited for a reactive or actor-based model rather than assuming virtual threads scale indefinitely.

## How We Will Validate It

- JMH benchmark comparing virtual-thread-per-request (Spring MVC) against WebFlux for the task/goal service under simulated Postgres + Kafka load; target ≥5,000 req/s at p99 < 200ms with near-linear scaling up to that point before considering a reactive rewrite.
- Load test (k6 or Gatling) driving 10,000 concurrent virtual threads against the identity and task services while capturing JFR thread-pinning events; target zero sustained pinning events in hot paths, with any occurrence root-caused and fixed (driver swap or lock removal) before it ships.
- CI gate requiring a successful GraalVM Native Image build of the desktop client on every release branch, with a smoke-test launch, to catch Java-25/GraalVM compatibility regressions early rather than at release time.
- A scheduled re-evaluation checkpoint at 6 months post-first-production-load (or at the first simulated 100k-user-equivalent load test, whichever comes first) to review JFR/production incident data specifically for virtual-thread and structured-concurrency-related failures before deciding whether the concurrency model holds at the next scale tier.
