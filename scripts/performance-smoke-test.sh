#!/usr/bin/env bash
set -euo pipefail

readonly REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly TARGET_URL="${LIFEOS_PERFORMANCE_GATEWAY_BASE_URL:-}"
readonly VUS="${LIFEOS_PERFORMANCE_VUS:-10}"
readonly DURATION="${LIFEOS_PERFORMANCE_DURATION:-15s}"
readonly SUMMARY_PATH="${LIFEOS_PERFORMANCE_SUMMARY_PATH:-${REPOSITORY_ROOT}/build/reports/performance/k6-summary.json}"
readonly K6_SCRIPT="${REPOSITORY_ROOT}/scripts/performance/readiness-smoke.js"

if [[ ! "${TARGET_URL}" =~ ^https://[^/?#]+(/[^?#]*)?$ ]]; then
    echo "LIFEOS_PERFORMANCE_GATEWAY_BASE_URL must be a canonical HTTPS URL" >&2
    exit 64
fi

if [[ ! "${VUS}" =~ ^[1-9][0-9]?$ ]] || (( VUS > 100 )); then
    echo "LIFEOS_PERFORMANCE_VUS must be between 1 and 100" >&2
    exit 64
fi

if [[ ! "${DURATION}" =~ ^([5-9]|[1-5][0-9]|60)s$ ]]; then
    echo "LIFEOS_PERFORMANCE_DURATION must be between 5s and 60s" >&2
    exit 64
fi

if [[ "${SUMMARY_PATH}" != "${REPOSITORY_ROOT}/"* ]]; then
    echo "LIFEOS_PERFORMANCE_SUMMARY_PATH must stay under the repository root" >&2
    exit 64
fi

mkdir -p "$(dirname "${SUMMARY_PATH}")"

if command -v k6 >/dev/null 2>&1; then
    k6 run \
        --quiet \
        --summary-export "${SUMMARY_PATH}" \
        --env "TARGET_URL=${TARGET_URL}" \
        --env "VUS=${VUS}" \
        --env "DURATION=${DURATION}" \
        "${K6_SCRIPT}"
elif command -v docker >/dev/null 2>&1; then
    docker run --rm \
        --volume "${REPOSITORY_ROOT}:/work" \
        --workdir /work \
        grafana/k6:0.55.0 \
        run \
        --quiet \
        --summary-export "/work/${SUMMARY_PATH#"${REPOSITORY_ROOT}"/}" \
        --env "TARGET_URL=${TARGET_URL}" \
        --env "VUS=${VUS}" \
        --env "DURATION=${DURATION}" \
        "/work/scripts/performance/readiness-smoke.js"
else
    echo "k6 or Docker is required to run the performance smoke test" >&2
    exit 69
fi

if [[ ! -s "${SUMMARY_PATH}" ]]; then
    echo "k6 did not produce a performance summary: ${SUMMARY_PATH}" >&2
    exit 65
fi

printf '%s\n' "Performance smoke report written to ${SUMMARY_PATH}"
