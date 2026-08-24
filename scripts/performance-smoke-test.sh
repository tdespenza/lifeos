#!/usr/bin/env bash
set -euo pipefail

REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
readonly REPOSITORY_ROOT
readonly TARGET_URL="${LIFEOS_PERFORMANCE_GATEWAY_MANAGEMENT_BASE_URL:-}"
readonly VUS="${LIFEOS_PERFORMANCE_VUS:-10}"
readonly DURATION="${LIFEOS_PERFORMANCE_DURATION:-15s}"
readonly K6_SCRIPT="${REPOSITORY_ROOT}/scripts/performance/readiness-smoke.js"
readonly K6_IMAGE="grafana/k6@sha256:b24f418fc99a26dd57904c952c03bfaf79462be18508acc45aafa07ff68e7df2"

temporary_summary_path=""

cleanup_temporary_summary() {
    # Keep a failed Docker run from leaving its private bind-mount source in the host temp directory.
    if [[ -n "${temporary_summary_path}" ]]; then
        rm -f -- "${temporary_summary_path}"
    fi
}

trap cleanup_temporary_summary EXIT

canonicalize_path() {
    # Resolve lexical path components and symlinks without creating any output directories first.
    # A hop limit makes malformed circular links an input error rather than an unbounded loop.
    local input_path="$1"
    local candidate_path component link_target resolved_component
    local symlink_hops=0
    local -a pending_components=()
    local -a resolved_components=()
    local -a link_components=()

    if [[ "${input_path}" == /* ]]; then
        candidate_path="${input_path}"
    else
        candidate_path="${REPOSITORY_ROOT}/${input_path}"
    fi
    IFS='/' read -r -a pending_components <<< "${candidate_path#/}"

    while [[ "${#pending_components[@]}" -gt 0 ]]; do
        component="${pending_components[0]}"
        pending_components=("${pending_components[@]:1}")
        case "${component}" in
            ''|.)
                continue
                ;;
            ..)
                if [[ "${#resolved_components[@]}" -gt 0 ]]; then
                    resolved_components=("${resolved_components[@]:0:${#resolved_components[@]} - 1}")
                fi
                continue
                ;;
        esac

        candidate_path="/"
        if [[ "${#resolved_components[@]}" -gt 0 ]]; then
            for resolved_component in "${resolved_components[@]}"; do
                candidate_path="${candidate_path%/}/${resolved_component}"
            done
        fi
        candidate_path="${candidate_path%/}/${component}"

        if [[ -L "${candidate_path}" ]]; then
            ((symlink_hops += 1))
            if (( symlink_hops > 40 )); then
                return 1
            fi
            link_target="$(readlink "${candidate_path}")" || return 1
            IFS='/' read -r -a link_components <<< "${link_target#/}"
            if [[ "${link_target}" == /* ]]; then
                resolved_components=()
            fi
            pending_components=("${link_components[@]-}" "${pending_components[@]-}")
        else
            resolved_components+=("${component}")
        fi
    done

    candidate_path="/"
    if [[ "${#resolved_components[@]}" -gt 0 ]]; then
        for resolved_component in "${resolved_components[@]}"; do
            candidate_path="${candidate_path%/}/${resolved_component}"
        done
    fi
    printf '%s\n' "${candidate_path}"
}

SUMMARY_PATH="$(canonicalize_path "${LIFEOS_PERFORMANCE_SUMMARY_PATH:-${REPOSITORY_ROOT}/build/reports/performance/k6-summary.json}")" || {
    echo "LIFEOS_PERFORMANCE_SUMMARY_PATH must resolve to a valid path" >&2
    exit 64
}
readonly SUMMARY_PATH

if [[ ! "${TARGET_URL}" =~ ^https://[^/?#]+(/[^?#]*)?$ ]]; then
    echo "LIFEOS_PERFORMANCE_GATEWAY_MANAGEMENT_BASE_URL must be a canonical HTTPS URL" >&2
    exit 64
fi

if [[ ! "${VUS}" =~ ^([1-9]|[1-9][0-9]|100)$ ]] || (( VUS > 100 )); then
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
# Empty an old report before running so a successful command that fails to emit a new summary does
# not accidentally pass the non-empty-report check below.
: > "${SUMMARY_PATH}"

if command -v k6 >/dev/null 2>&1; then
    k6 run \
        --quiet \
        --summary-export "${SUMMARY_PATH}" \
        --env "TARGET_URL=${TARGET_URL}" \
        --env "VUS=${VUS}" \
        --env "DURATION=${DURATION}" \
        "${K6_SCRIPT}"
elif command -v docker >/dev/null 2>&1; then
    temporary_summary_path="$(mktemp "${TMPDIR:-/tmp}/lifeos-k6-summary.XXXXXX")" || {
        echo "Unable to create a temporary k6 summary file" >&2
        exit 73
    }
    container_summary_path="/tmp/$(basename "${temporary_summary_path}")"

    docker run --rm \
        --volume "${REPOSITORY_ROOT}:/work:ro" \
        --volume "${temporary_summary_path}:${container_summary_path}" \
        --workdir /work \
        "${K6_IMAGE}" \
        run \
        --quiet \
        --summary-export "${container_summary_path}" \
        --env "TARGET_URL=${TARGET_URL}" \
        --env "VUS=${VUS}" \
        --env "DURATION=${DURATION}" \
        "/work/scripts/performance/readiness-smoke.js"

    if [[ ! -s "${temporary_summary_path}" ]]; then
        echo "k6 did not produce a performance summary: ${SUMMARY_PATH}" >&2
        exit 65
    fi
    mv -- "${temporary_summary_path}" "${SUMMARY_PATH}"
    temporary_summary_path=""
else
    echo "k6 or Docker is required to run the performance smoke test" >&2
    exit 69
fi

if [[ ! -s "${SUMMARY_PATH}" ]]; then
    echo "k6 did not produce a performance summary: ${SUMMARY_PATH}" >&2
    exit 65
fi

printf '%s\n' "Performance smoke report written to ${SUMMARY_PATH}"
