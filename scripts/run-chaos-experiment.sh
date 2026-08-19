#!/usr/bin/env bash
set -euo pipefail

readonly WEBHOOK_URL="${LIFEOS_CHAOS_EXPERIMENT_WEBHOOK_URL:-}"
readonly GATEWAY_URL="${LIFEOS_CHAOS_GATEWAY_BASE_URL:-}"
readonly IDENTITY_URL="${LIFEOS_CHAOS_IDENTITY_BASE_URL:-}"
readonly TASK_GOAL_URL="${LIFEOS_CHAOS_TASK_GOAL_BASE_URL:-}"
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

validate_url LIFEOS_CHAOS_EXPERIMENT_WEBHOOK_URL "${WEBHOOK_URL}"
validate_url LIFEOS_CHAOS_GATEWAY_BASE_URL "${GATEWAY_URL}"
validate_url LIFEOS_CHAOS_IDENTITY_BASE_URL "${IDENTITY_URL}"
validate_url LIFEOS_CHAOS_TASK_GOAL_BASE_URL "${TASK_GOAL_URL}"

payload="$(jq -cn \
    --arg runId "${RUN_ID}" \
    --arg experiment "dependency-isolation-readiness" \
    --arg gateway "${GATEWAY_URL}" \
    --arg identity "${IDENTITY_URL}" \
    --arg taskGoal "${TASK_GOAL_URL}" \
    '{runId: $runId, experiment: $experiment, targets: {gateway: $gateway, identity: $identity, taskGoal: $taskGoal}}')"

# The external runner must inject and recover a pre-approved dependency failure, wait for recovery,
# and return non-2xx if the experiment or rollback fails. Do not print its URL or response.
curl \
    --fail \
    --silent \
    --show-error \
    --location \
    --proto '=https' \
    --connect-timeout 10 \
    --max-time 300 \
    --retry 1 \
    --header 'Content-Type: application/json' \
    --data "${payload}" \
    --output /dev/null \
    "${WEBHOOK_URL}"

for target in "${GATEWAY_URL}" "${IDENTITY_URL}" "${TASK_GOAL_URL}"; do
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
        "${target%/}/actuator/health/readiness" \
        | jq --exit-status '.status == "UP"' >/dev/null
done

printf '%s\n' "Chaos experiment completed and all services recovered"
