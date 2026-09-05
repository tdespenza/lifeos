#!/usr/bin/env bash
set -euo pipefail

readonly WEBHOOK_URL="${LIFEOS_CHAOS_EXPERIMENT_WEBHOOK_URL:-}"
readonly GATEWAY_HEALTH_URL="${LIFEOS_CHAOS_GATEWAY_HEALTH_URL:-}"
readonly IDENTITY_HEALTH_URL="${LIFEOS_CHAOS_IDENTITY_HEALTH_URL:-}"
readonly TASK_GOAL_HEALTH_URL="${LIFEOS_CHAOS_TASK_GOAL_HEALTH_URL:-}"
readonly SCRIPT_PATH="${BASH_SOURCE[0]}"
if [[ "${SCRIPT_PATH}" == */* ]]; then
    SCRIPT_DIRECTORY="${SCRIPT_PATH%/*}"
else
    SCRIPT_DIRECTORY="."
fi
if ! SCRIPT_DIRECTORY="$(cd -- "${SCRIPT_DIRECTORY}" && pwd -P)"; then
    echo "Unable to resolve the chaos-experiment script directory" >&2
    exit 69
fi
readonly SCRIPT_DIRECTORY
readonly HTTPS_AUTHORITY_VALIDATION_SCRIPT="${SCRIPT_DIRECTORY}/https-authority-validation.sh"
if [[ ! -f "${HTTPS_AUTHORITY_VALIDATION_SCRIPT}" || ! -r "${HTTPS_AUTHORITY_VALIDATION_SCRIPT}" ]]; then
    echo "HTTPS authority validation library is required" >&2
    exit 69
fi
# The library path is derived from this script's directory and is checked above.
# shellcheck disable=SC1090,SC1091
source "${HTTPS_AUTHORITY_VALIDATION_SCRIPT}"

if [[ -z "${GITHUB_RUN_ID:-}" ]] && ! command -v date >/dev/null 2>&1; then
    echo "date is required to generate the local chaos experiment run ID" >&2
    exit 69
fi

readonly RUN_ID="${GITHUB_RUN_ID:-local-$(date +%s)}"

cleanup_health_response_file() {
    local exit_status="$1"

    trap - EXIT HUP INT TERM
    if [[ -n "${HEALTH_RESPONSE_FILE}" ]]; then
        rm -f -- "${HEALTH_RESPONSE_FILE}" || true
    fi
    exit "${exit_status}"
}
trap 'cleanup_health_response_file "$?"' EXIT
trap 'cleanup_health_response_file 129' HUP
trap 'cleanup_health_response_file 130' INT
trap 'cleanup_health_response_file 143' TERM

if ! command -v curl >/dev/null 2>&1 \
    || ! command -v head >/dev/null 2>&1 \
    || ! command -v jq >/dev/null 2>&1 \
    || ! command -v sleep >/dev/null 2>&1 \
    || ! command -v mktemp >/dev/null 2>&1 \
    || ! command -v rm >/dev/null 2>&1 \
    || ! command -v wc >/dev/null 2>&1; then
    echo "curl, head, jq, sleep, mktemp, rm, and wc are required to run the chaos experiment" >&2
    exit 69
fi

validate_url() {
    local variable_name="$1"
    local value="$2"
    if [[ ! "${value}" =~ ^https://[^/@?#]+(/[^?#]*)?$ ]] \
        || ! has_valid_https_authority "${value}"; then
        echo "${variable_name} must be a canonical HTTPS URL without query or fragment" >&2
        exit 64
    fi
}

validate_readiness_url() {
    local variable_name="$1"
    local value="$2"
    if [[ ! "${value}" =~ ^https://[^/@?#]+(/[^?#]*)?/actuator/health/readiness$ ]] \
        || ! has_valid_https_authority "${value}"; then
        echo "${variable_name} must be a canonical HTTPS actuator readiness endpoint" >&2
        exit 64
    fi
}

validate_health_url() {
    local variable_name="$1"
    local value="$2"
    if [[ ! "${value}" =~ ^https://[^/@?#]+(/[^?#]*)?/actuator/health$ ]] \
        || ! has_valid_https_authority "${value}"; then
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
    --disable \
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

wait_for_health gateway "${GATEWAY_HEALTH_URL}" 'Chaos recovery health for '
wait_for_health identity "${IDENTITY_HEALTH_URL}" 'Chaos recovery health for '
wait_for_health task-goal "${TASK_GOAL_HEALTH_URL}" 'Chaos recovery health for '

printf '%s\n' "Chaos experiment completed and all services recovered"
