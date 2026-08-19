#!/usr/bin/env bash

# Static contract check for the opt-in local observability profile. It does not start Docker or
# require service credentials, so it is safe to run in CI and from an unprovisioned workstation.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
compose_file="$repo_root/infrastructure/docker-compose/docker-compose.yml"
collector_file="$repo_root/infrastructure/observability/otel-collector/config.yaml"
prometheus_file="$repo_root/infrastructure/observability/prometheus/prometheus.yml"
promtail_file="$repo_root/infrastructure/observability/promtail/config.yaml"
loki_file="$repo_root/infrastructure/observability/loki/config.yaml"
task_goal_file="$repo_root/services/task-goal-service/src/main/resources/application.yml"
dashboard_file="$repo_root/infrastructure/observability/grafana/dashboards/epic-2-gateway.json"

for required_command in ruby jq; do
    if ! command -v "$required_command" >/dev/null 2>&1; then
        echo "error: $required_command is required to verify observability configuration" >&2
        exit 1
    fi
done

ruby -ryaml - "$compose_file" "$collector_file" "$prometheus_file" "$promtail_file" "$loki_file" "$task_goal_file" <<'RUBY'
def load_yaml(path)
  YAML.safe_load(File.read(path), aliases: false)
rescue Psych::Exception => error
  abort "error: invalid YAML in #{path}: #{error.message}"
end

def assert_contract(condition, message)
  abort "error: #{message}" unless condition
end

compose, collector, prometheus, promtail, loki, task_goal = ARGV.map { |path| load_yaml(path) }
services = compose.fetch("services")
observability_services = %w[otel-collector prometheus loki tempo promtail grafana]

observability_services.each do |name|
  service = services[name]
  assert_contract(!service.nil?, "compose service #{name} is missing")
  assert_contract(service["profiles"] == ["observability"], "#{name} must be opt-in via the observability profile")
  assert_contract(Array(service["networks"]).include?("observability"), "#{name} must join the observability network")
end

%w[otel-collector prometheus loki tempo grafana].each do |name|
  ports = Array(services.fetch(name)["ports"])
  assert_contract(!ports.empty?, "#{name} must expose its local verification endpoint")
  assert_contract(ports.all? { |port| port.start_with?("127.0.0.1:") }, "#{name} ports must bind only to loopback")
end

%w[postgres redis].each do |name|
  ports = Array(services.fetch(name)["ports"])
  assert_contract(!ports.empty?, "#{name} must expose its local development port")
  assert_contract(ports.all? { |port| port.start_with?("127.0.0.1:") }, "#{name} ports must bind only to loopback")
end

prometheus_service = services.fetch("prometheus")
assert_contract(Array(prometheus_service["extra_hosts"]).include?("host.docker.internal:host-gateway"),
       "Prometheus must resolve host.docker.internal on Linux")
assert_contract(prometheus_service.fetch("volumes").include?("../observability/prometheus/rules:/etc/prometheus/rules:ro"),
       "Prometheus alert rules must be mounted read-only")
assert_contract(services.fetch("promtail").fetch("volumes").include?(
         "${LIFEOS_OBSERVABILITY_LOG_DIRECTORY:-/tmp/lifeos-observability}:/var/log/lifeos:ro"
       ), "Promtail must read host-process logs from a configurable read-only directory")
grafana = services.fetch("grafana")
assert_contract(grafana.fetch("environment").fetch("GF_SECURITY_ADMIN_PASSWORD") == "${GRAFANA_ADMIN_PASSWORD:-}",
       "Grafana password interpolation must not block inactive profiles")
assert_contract(grafana.fetch("entrypoint") == ["/bin/sh", "-ec"],
       "Grafana must enforce its password with the runtime shell entrypoint")
grafana_command = Array(grafana.fetch("command")).join("\n")
assert_contract(grafana_command.include?("$$GF_SECURITY_ADMIN_PASSWORD") && grafana_command.include?("exec /run.sh"),
       "Grafana must fail closed before its normal startup when the password is blank")
assert_contract(compose.fetch("networks").fetch("observability").fetch("name") == "lifeos-observability",
       "observability network name must be stable for local tooling")

pipelines = collector.fetch("service").fetch("pipelines")
%w[traces metrics logs].each do |signal|
  pipeline = pipelines[signal]
  assert_contract(!pipeline.nil?, "collector must define the #{signal} pipeline")
  assert_contract(Array(pipeline["receivers"]).include?("otlp"), "collector #{signal} pipeline must receive OTLP")
  assert_contract(Array(pipeline["processors"]).include?("memory_limiter"), "collector #{signal} pipeline must bound memory")
end
assert_contract(Array(pipelines.fetch("traces").fetch("exporters")).include?("otlphttp/tempo"),
       "collector traces must export to Tempo")
assert_contract(Array(pipelines.fetch("logs").fetch("exporters")).include?("otlphttp/loki"),
       "collector logs must export to Loki")

scrapes = prometheus.fetch("scrape_configs").to_h { |scrape| [scrape.fetch("job_name"), scrape] }
{
  "lifeos-gateway" => "host.docker.internal:9080",
  "lifeos-identity" => "host.docker.internal:9081",
  "lifeos-task-goal" => "host.docker.internal:9082"
}.each do |job, target|
  configured_targets = scrapes.fetch(job).fetch("static_configs").flat_map { |config| config.fetch("targets") }
  assert_contract(configured_targets.include?(target), "#{job} must target #{target} in the host-process workflow")
end

host_log_job = promtail.fetch("scrape_configs").find { |scrape| scrape["job_name"] == "lifeos-host-processes" }
assert_contract(!host_log_job.nil?, "Promtail must collect the documented host-process log files")
assert_contract(host_log_job.fetch("static_configs").first.fetch("labels").fetch("__path__") == "/var/log/lifeos/*.log",
       "Promtail host log glob must match the mounted directory")
container_log_job = promtail.fetch("scrape_configs").find { |scrape| scrape["job_name"] == "lifeos-containers" }
assert_contract(!container_log_job.nil?, "Promtail container log collection must remain available")
keep_rule = container_log_job.fetch("relabel_configs").find { |rule| rule["action"] == "keep" }
assert_contract(keep_rule && keep_rule["source_labels"] == ["__meta_docker_container_label_com_lifeos_observability_logs"],
       "Promtail must require explicit container log collection opt-in")
assert_contract(loki.fetch("limits_config").fetch("allow_structured_metadata"),
       "Loki must accept OTLP structured metadata")

management = task_goal.fetch("management")
assert_contract(management.dig("server", "port") == "${TASK_GOAL_MANAGEMENT_PORT:9082}",
       "task-goal management port is missing")
assert_contract(management.dig("server", "address") == "${TASK_GOAL_MANAGEMENT_ADDRESS:127.0.0.1}",
       "task-goal management listener must default to loopback")
assert_contract(management.dig("endpoint", "prometheus", "access") == "unrestricted",
       "task-goal Prometheus endpoint must be enabled")
assert_contract(management.dig("otlp", "tracing", "endpoint") == "${OTEL_EXPORTER_OTLP_TRACES_ENDPOINT:http://localhost:4318/v1/traces}",
       "task-goal OTLP endpoint is missing")
assert_contract(task_goal.dig("logging", "structured", "format", "console") == "ecs",
       "task-goal ECS logging is missing")
RUBY

jq -e '
  .uid == "lifeos-epic-2-gateway"
  and .title == "LifeOS Epic 2 Gateway"
  and ([.panels[].targets[]?.expr] | any(test("gateway_upstream_failures_total")))
' "$dashboard_file" >/dev/null

echo "Observability static configuration checks passed."
