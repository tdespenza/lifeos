# Observability runbook

## Scope and boundary

The observability Compose profile is a **local-only reference implementation** for the Epic 2
gateway path. It provides an OpenTelemetry Collector, Prometheus, Loki, Tempo, Promtail, Grafana,
a pre-provisioned Epic 2 dashboard, and local alert rules. It gives developers a repeatable way to
inspect a gateway → identity → task-goal request; it is not a deployed production telemetry platform.

The profile does not satisfy a production rollout of NFR13–NFR16 by itself. In particular, it has no
production TLS or mTLS configuration, secret-manager integration, long-term object storage, backup
or disaster-recovery policy, tenant isolation, alert routing/on-call integration, capacity SLO, or
production log-agent deployment. The Promtail Docker-socket integration is intentionally local-only.

## What is collected

| Signal | Local path | Bounded behavior |
| --- | --- | --- |
| Traces | Spring Boot OTLP → Collector → Tempo | Collector memory limiter and bounded exporter queues |
| Metrics | Prometheus scrapes private Actuator endpoints | 15-second scrape interval and seven-day local retention |
| Host-process ECS logs | Service stdout files → Promtail → Loki | The mounted log directory is read-only to Promtail |
| Container ECS logs | Explicitly labelled containers → Promtail → Loki | Promtail rejects containers without the exact opt-in label |

Gateway, identity, and task-goal use ECS structured console logging. Trace IDs remain structured log
fields rather than Loki labels, so a user ID, correlation ID, trace ID, or other unbounded value
cannot create label-cardinality pressure. Grafana’s Loki data source links an ECS trace ID to Tempo.

## Start the local profile

1. Copy [the local Compose template](../../infrastructure/docker-compose/.env.example) to the
   ignored infrastructure/docker-compose/.env file and supply local-only database values. Compose
   interpolates the whole file, so these values are required even when starting only observability.

2. Create a host directory for ECS service output and provide a local Grafana administrator password:

    mkdir -p /tmp/lifeos-observability
   export GRAFANA_ADMIN_PASSWORD="$(openssl rand -base64 32)"

   The base PostgreSQL/Redis Compose path does not require this variable. Grafana is profile-gated
   and fails closed at container startup if the observability profile is selected without a
   non-empty password; this avoids an insecure Grafana default while keeping normal Compose
   validation and data-service startup independent of observability credentials.

3. Start the explicitly named observability services:

    docker compose --env-file infrastructure/docker-compose/.env \
      -f infrastructure/docker-compose/docker-compose.yml \
      --profile observability up -d otel-collector prometheus loki tempo promtail grafana

The profile publishes only loopback ports: Grafana at http://localhost:3000, Prometheus at
http://localhost:9090, Loki at http://localhost:3100, Tempo at http://localhost:3200, and OTLP at
localhost:4317 (gRPC) / localhost:4318 (HTTP). PostgreSQL and Redis are also bound only to
localhost. Never expose these development defaults outside the local machine.

## Connect the currently built services

The default Prometheus targets use host.docker.internal rather than nonexistent service DNS names.
The documented **host-process metrics workflow is supported on Docker Desktop (macOS and Windows)**:
that alias can reach the existing services while their management listeners remain bound to
127.0.0.1. Confirm target health in Prometheus after startup because local firewall or Docker
Desktop settings can still block the connection. The Compose host-gateway mapping supplies the same
alias on native Linux, but a Linux container normally cannot reach a host process bound only to
127.0.0.1 through that bridge address. On native Linux, the telemetry containers, traces, and host
log collection remain usable, but the three host-process Prometheus jobs are intentionally not a
supported path in this reference profile. Use a reviewed loopback-aware proxy restricted to the
Docker bridge, or run the services in a private deployment network, before enabling those jobs; do
not change a management listener to 0.0.0.0 merely to make local scraping work.

For host-run services, retain their safe default management listener addresses and send traces to
the collector:

    export OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=http://localhost:4318/v1/traces
    export LIFEOS_OBSERVABILITY_LOG_DIRECTORY=/tmp/lifeos-observability

Start each service with its normal local secrets and redirect only its ECS stdout to a dedicated
file, for example gateway.log, identity.log, or task-goal.log in that directory. Promtail tails
/var/log/lifeos/*.log from a read-only mount. Do not redirect credentials or arbitrary shell output
into those files.

A future containerized service can opt into local log collection only with this label:

    labels:
      com.lifeos.observability.logs: "true"

Promtail drops every container without that exact label. A production log agent must instead use the
platform’s supported read-only workload-log integration; it must not mount the Docker socket.

## Verify an Epic 2 request

After the services are running and a valid authenticated gateway request reaches /api/v1/goals
(with the host-process metrics workflow on Docker Desktop, or equivalent private deployment
networking on Linux):

1. In Prometheus, confirm the lifeos-gateway, lifeos-identity, and lifeos-task-goal jobs are up.
   Each job scrapes only /actuator/prometheus on its private management port.
2. Open Grafana’s **LifeOS / LifeOS Epic 2 Gateway** dashboard to inspect request rate, p95 latency,
   in-flight requests, upstream failures, and rate-limit rejections.
3. In Tempo Explore, find the trace beginning with lifeos-gateway. The protected goal path should
   include the gateway’s identity validation and task-goal’s identity calls when those branches run.
4. From a matching ECS log line in Loki, use the TraceID derived field to open its Tempo trace.
5. Deliberately exercise a controlled downstream failure only in a local environment. The
   GatewayUpstreamFailures rule should become pending after two minutes; no alert is routed
   automatically because the local profile intentionally has no Alertmanager.

## Configuration checks

Run the static contract test without Docker, network access, service secrets, or running services:

    bash scripts/verify-observability-stack.sh

Validate the normal Compose path without a Grafana credential, then validate the observability
profile with one:

    docker compose --env-file infrastructure/docker-compose/.env \
      -f infrastructure/docker-compose/docker-compose.yml config -q

    GRAFANA_ADMIN_PASSWORD=local-validation-only \
      docker compose --env-file infrastructure/docker-compose/.env \
      -f infrastructure/docker-compose/docker-compose.yml \
      --profile observability config -q

The static test validates YAML/JSON syntax and critical topology invariants. It cannot prove image
runtime compatibility, real service reachability, or trace/log completeness; perform the preceding
end-to-end check before treating a deployment as observability-ready.
