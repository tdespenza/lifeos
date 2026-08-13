# How I'd Debug a Production Issue in LifeOS

Let me be upfront: "production" for LifeOS today means a local docker-compose environment, not a deployed, traffic-serving system. So this is really "how I'd debug an issue in the running services as they exist now," plus how that story changes once the planned observability stack lands. I think being honest about that gap is itself the more interesting interview answer.

## Today's reality

The two services are at different observability stages. `identity-service` emits ECS-structured logs, records Micrometer/Prometheus metrics, and exports OpenTelemetry traces from its private management topology. `task-goal-service` still exposes only `health` and `info` and uses standard Spring logging. There is no deployed collector, log aggregation, dashboard, or end-to-end trace across the Task/Goal-to-Identity authorization call yet, so most cross-service investigation still means comparing bounded local logs and timestamps.

Given that, my actual debugging workflow today is:

1. **Confirm the process is alive and the dependency it needs is reachable.** `/actuator/health` tells me if the JVM is up and — since it aggregates the datasource health indicator — whether Postgres is reachable. That's the first fork in the road: is this a "service is down" problem or a "service is up but returning wrong/slow answers" problem?
2. **Reproduce against the actual endpoint.** Both services are small enough that I can exercise the same request locally: identity owns login, durable session validation, and authorization decisions; Task/Goal owns authenticated, owner/tenant-scoped goal access and dependency ordering.
3. **Correlate the boundary safely.** Identity's structured logs and traces help diagnose its own work, but Task/Goal has not yet received the same instrumentation or a deployed tracing backend. For the current two-service path I compare timestamps and bounded event/reason fields, never bearer tokens, proofs, or resource contents.
4. **Check the database and migration history directly.** Postgres is the system of record, Flyway owns schema evolution, and Hibernate runs `ddl-auto: validate`. I check `flyway_schema_history`, the expected schema version, and application readiness before treating a surprising state as an application-data bug.

The honest failure mode here: cross-service issues, intermittent issues, and anything that isn't reproducible on demand are genuinely hard to debug with this toolset. That's not an accident I'm ignoring — it's the reason ADR-018 exists.

## Where this is going

The planned stack (OpenTelemetry SDK/Collector in every service, Prometheus for metrics, Loki for logs, Tempo for traces, all correlated by trace ID in Grafana) turns step 3 above from "eyeball two terminals" into "click the trace, see every span across every service it touched, jump straight to the correlated log lines." Once there's real concurrent fan-out (e.g., a dashboard aggregation using structured concurrency across several services), that's exactly the shape of request where a single-process log tail stops working and distributed tracing becomes non-optional. I haven't built that yet because I don't have a fan-out code path to instrument meaningfully — I'd rather add tracing when there's a real multi-hop call graph to prove it against than retrofit it onto two independent CRUD services where it wouldn't demonstrate much.

**Relevant ADRs:** [ADR-018](../adr/ADR-018-use-opentelemetry-for-observability.md), [ADR-008](../adr/ADR-008-use-postgresql-as-system-of-record.md)
