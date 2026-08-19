# How I'd Debug a Production Issue in LifeOS

Let me be upfront: LifeOS is not a deployed, traffic-serving production system yet. This is how I
would debug the current service modules using the local observability reference profile where
appropriate, and how that posture still differs from a production telemetry deployment.

## Today's reality

All twelve current Spring Boot services expose ECS-structured logs, Micrometer/Prometheus metrics,
OpenTelemetry traces, and health/liveness/readiness endpoints on private management listeners. The
opt-in local Compose profile collects the initial gateway/identity/task-goal targets in Prometheus,
Loki, Tempo, and Grafana; its exact
startup and verification workflow lives in the
[observability runbook](../operations/observability.md). That profile is a development aid, not a
production telemetry deployment: it lacks managed storage, alert routing, production log-agent
integration, and the relevant TLS/mTLS controls.

Given that, my actual debugging workflow today is:

1. **Confirm the process is alive and the dependency it needs is reachable.** `/actuator/health` tells me if the JVM is up and — since it aggregates the datasource health indicator — whether Postgres is reachable. That's the first fork in the road: is this a "service is down" problem or a "service is up but returning wrong/slow answers" problem?
2. **Reproduce against the actual endpoint.** Gateway owns routing/rate limits; Identity owns
   login, durable session validation, and authorization decisions; and the routed Task/Goal,
   Profile, Calendar, Finance, and Notification boundaries own their local state. Kafka paths are
   debugged separately through Calendar/Notification outbox and consumer evidence.
3. **Correlate the boundary safely.** With the local profile running, I start at the gateway trace in Tempo and use Grafana/Loki trace links to inspect the matching bounded logs. Without it, I use the shared `X-Correlation-ID` and safe event/reason fields—never bearer tokens, proofs, or resource contents.
4. **Check the database and migration history directly.** Postgres is the system of record, Flyway owns schema evolution, and Hibernate runs `ddl-auto: validate`. I check `flyway_schema_history`, the expected schema version, and application readiness before treating a surprising state as an application-data bug.

The honest remaining failure mode is operational rather than instrumentation-free: cross-service
issues still lack a hardened, long-lived production backend and alerting path. That's not an
accident I'm ignoring—it is the remaining platform work captured by ADR-018.

## Where this is going

The local OpenTelemetry/Prometheus/Loki/Tempo/Grafana profile already turns step 3 into "click the
trace, then jump to its correlated log lines" for the initial gateway-to-Identity/TaskGoal path.
A production deployment must add the operational controls called out in the runbook and scrape the
newer service targets. Once there is real concurrent fan-out (for example, dashboard aggregation
using structured concurrency across several services), distributed tracing becomes even more
important because a single-process log tail cannot explain fan-out cancellation or partial failure.

**Relevant ADRs:** [ADR-018](../adr/ADR-018-use-opentelemetry-for-observability.md), [ADR-008](../adr/ADR-008-use-postgresql-as-system-of-record.md)
