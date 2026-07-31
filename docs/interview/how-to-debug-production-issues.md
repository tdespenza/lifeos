# How I'd Debug a Production Issue in LifeOS

Let me be upfront: "production" for LifeOS today means a local docker-compose environment, not a deployed, traffic-serving system. So this is really "how I'd debug an issue in the running services as they exist now," plus how that story changes once the planned observability stack lands. I think being honest about that gap is itself the more interesting interview answer.

## Today's reality

Right now I have exactly two tools: Spring Boot Actuator's `/actuator/health` endpoint (both `identity-service` and `task-goal-service` expose only `health` and `info`, nothing else) and whatever gets printed to stdout by default Spring Boot logging. There's no structured JSON logging, no correlation/trace IDs threaded through requests, no metrics endpoint exposed, and no log aggregation — if something breaks, I'm reading raw console output from a single process.

Given that, my actual debugging workflow today is:

1. **Confirm the process is alive and the dependency it needs is reachable.** `/actuator/health` tells me if the JVM is up and — since it aggregates the datasource health indicator — whether Postgres is reachable. That's the first fork in the road: is this a "service is down" problem or a "service is up but returning wrong/slow answers" problem?
2. **Reproduce against the actual endpoint.** Both services are small enough (identity is registration-only, task-goal is goal CRUD plus the Kahn's-algorithm dependency-ordering endpoint) that I can usually hit the same request with curl and watch stdout in real time.
3. **Read the stack trace and correlate it manually.** Without trace IDs, if I need to correlate a failure across identity-service and task-goal-service, I'm eyeballing timestamps across two terminal windows. That's fine at two services; it stops being fine well before eleven.
4. **Check the database directly.** With Postgres as the system of record and Hibernate `ddl-auto: update`, a decent chunk of "weird state" bugs are schema or data issues I can just query for.

The honest failure mode here: cross-service issues, intermittent issues, and anything that isn't reproducible on demand are genuinely hard to debug with this toolset. That's not an accident I'm ignoring — it's the reason ADR-018 exists.

## Where this is going

The planned stack (OpenTelemetry SDK/Collector in every service, Prometheus for metrics, Loki for logs, Tempo for traces, all correlated by trace ID in Grafana) turns step 3 above from "eyeball two terminals" into "click the trace, see every span across every service it touched, jump straight to the correlated log lines." Once there's real concurrent fan-out (e.g., a dashboard aggregation using structured concurrency across several services), that's exactly the shape of request where a single-process log tail stops working and distributed tracing becomes non-optional. I haven't built that yet because I don't have a fan-out code path to instrument meaningfully — I'd rather add tracing when there's a real multi-hop call graph to prove it against than retrofit it onto two independent CRUD services where it wouldn't demonstrate much.

**Relevant ADRs:** [ADR-018](../adr/ADR-018-use-opentelemetry-for-observability.md), [ADR-008](../adr/ADR-008-use-postgresql-as-system-of-record.md)
