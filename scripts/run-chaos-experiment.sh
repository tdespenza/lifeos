#!/usr/bin/env bash
set -euo pipefail

readonly WEBHOOK_URL="${LIFEOS_CHAOS_EXPERIMENT_WEBHOOK_URL:-}"
readonly GATEWAY_HEALTH_URL="${LIFEOS_CHAOS_GATEWAY_HEALTH_URL:-}"
readonly IDENTITY_HEALTH_URL="${LIFEOS_CHAOS_IDENTITY_HEALTH_URL:-}"
readonly TASK_GOAL_HEALTH_URL="${LIFEOS_CHAOS_TASK_GOAL_HEALTH_URL:-}"
readonly RUN_ID="${GITHUB_RUN_ID:-local-$(date +%s)}"

if ! command -v curl >/dev/null 2>&1 || ! command -v jq >/dev/null 2>&1; then
    echo "curl and jq are required to run the chaos experiment" >&2
    exit 69
fi

validate_url() {
    local variable_name="$1"
    local value="$2"
    if [[ ! "${value}" =~ ^https://[^/?#]+(/[^?#]*)?$ ]]; then
        echo "${variable_name} must be a canonical HTTPS URL without query or fragment" >&2
        exit 64
    fi
}

validate_readiness_url() {
    local variable_name="$1"
    local value="$2"
    if [[ ! "${value}" =~ ^https://[^/?#]+(/[^?#]*)?/actuator/health/readiness$ ]]; then
        echo "${variable_name} must be a canonical HTTPS actuator readiness endpoint" >&2
        exit 64
    fi
}

validate_health_url() {
    local variable_name="$1"
    local value="$2"
    if [[ ! "${value}" =~ ^https://[^/?#]+(/[^?#]*)?/actuator/health$ ]]; then
        echo "${variable_name} must be a canonical HTTPS actuator health endpoint" >&2
        exit 64
    fi
}

validate_url LIFEOS_CHAOS_EXPERIMENT_WEBHOOK_URL "${WEBHOOK_URL}"
validate_readiness_url LIFEOS_CHAOS_GATEWAY_HEALTH_URL "${GATEWAY_HEALTH_URL}"
validate_readiness_url LIFEOS_CHAOS_IDENTITY_HEALTH_URL "${IDENTITY_HEALTH_URL}"
validate_health_url LIFEOS_CHAOS_TASK_GOAL_HEALTH_URL "${TASK_GOAL_HEALTH_URL}"

payload="$(jq -cn \
    --arg runId "${RUN_ID}" \
    --arg experiment "dependency-isolation-readiness" \
    --arg gateway "${GATEWAY_HEALTH_URL}" \
    --arg identity "${IDENTITY_HEALTH_URL}" \
    --arg taskGoal "${TASK_GOAL_HEALTH_URL}" \
    '{runId: $runId, experiment: $experiment, targets: {gateway: $gateway, identity: $identity, taskGoal: $taskGoal}}')"

# The external runner must inject and recover a pre-approved dependency failure, wait for recovery,
# and return non-2xx if the experiment or rollback fails. Do not print its URL or response.
curl \
    --fail \
    --silent \
    --show-error \
    --location \
    --max-redirs 0 \
    --proto '=https' \
    --connect-timeout 10 \
    --max-time 300 \
    --header 'Content-Type: application/json' \
    --data "${payload}" \
    --output /dev/null \
    "${WEBHOOK_URL}"

for target in "${GATEWAY_HEALTH_URL}" "${IDENTITY_HEALTH_URL}" "${TASK_GOAL_HEALTH_URL}"; do
    curl \
        --fail \
        --silent \
        --show-error \
        --location \
        --proto '=https' \
        --connect-timeout 10 \
        --max-time 20 \
        --retry 5 \
        --retry-all-errors \
        "${target}" \
        | jq --exit-status '.status == "UP"' >/dev/null
done

printf '%s\n' "Chaos experiment completed and all services recovered"
