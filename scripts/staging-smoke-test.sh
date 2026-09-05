#!/usr/bin/env bash
set -euo pipefail

if ! command -v dirname >/dev/null 2>&1; then
    echo "dirname is required to resolve the repository root" >&2
    exit 69
fi

REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly REPOSITORY_ROOT
readonly HTTPS_AUTHORITY_VALIDATION_SCRIPT="${REPOSITORY_ROOT}/scripts/https-authority-validation.sh"
if [[ ! -f "${HTTPS_AUTHORITY_VALIDATION_SCRIPT}" || ! -r "${HTTPS_AUTHORITY_VALIDATION_SCRIPT}" ]]; then
    echo "HTTPS authority validation library is required" >&2
    exit 69
fi
# The library path is derived from the repository root and is checked above.
# shellcheck disable=SC1090,SC1091
source "${HTTPS_AUTHORITY_VALIDATION_SCRIPT}"
readonly SERVICE_HEALTH_URLS_JSON="${STAGING_SERVICE_HEALTH_URLS_JSON:-}"
SERVICES=()

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
    echo "curl, head, jq, sleep, mktemp, rm, and wc are required to run the staging smoke test" >&2
    exit 69
fi

if [[ -z "${SERVICE_HEALTH_URLS_JSON}" ]]; then
    echo "STAGING_SERVICE_HEALTH_URLS_JSON is required for the staging smoke test" >&2
    exit 64
fi

if ! jq --exit-status 'type == "object" and all(.[]; type == "string")' \
    <<< "${SERVICE_HEALTH_URLS_JSON}" >/dev/null; then
    echo "STAGING_SERVICE_HEALTH_URLS_JSON must map service names to HTTPS actuator health URLs" >&2
    exit 64
fi

for service_discovery_command in find basename sort; do
    if ! command -v "${service_discovery_command}" >/dev/null 2>&1; then
        echo "${service_discovery_command} is required to discover service Dockerfiles" >&2
        exit 69
    fi
done

if ! discovered_services="$(find "${REPOSITORY_ROOT}/infrastructure/docker" -maxdepth 1 -type f -name '*.Dockerfile' \
    -exec basename {} .Dockerfile \; | sort)"; then
    echo "Failed to discover service Dockerfiles" >&2
    exit 69
fi

# Command substitution removes trailing newlines. Do not feed an empty successful discovery into
# the loop because a here-string would otherwise create one empty service instead of preserving
# the existing no-Dockerfiles failure below.
if [[ -n "${discovered_services}" ]]; then
    while IFS= read -r service; do
        SERVICES+=("${service}")
    done <<< "${discovered_services}"
fi

if [[ "${#SERVICES[@]}" -eq 0 ]]; then
    echo "No service Dockerfiles found in infrastructure/docker" >&2
    exit 66
fi

for service in "${SERVICES[@]}"; do
    if ! health_url="$(jq --raw-output --exit-status --arg service "${service}" \
        '.[$service] // empty' <<< "${SERVICE_HEALTH_URLS_JSON}")" || [[ -z "${health_url}" ]]; then
        echo "STAGING_SERVICE_HEALTH_URLS_JSON is missing an HTTPS actuator health URL for ${service}" >&2
        exit 64
    fi

    if [[ ! "${health_url}" =~ ^https://[^/@?#]+(/[^?#]*)?/actuator/health(/(readiness|liveness))?$ ]] \
        || ! has_valid_https_authority "${health_url}"; then
        echo "Staging health URL for ${service} must be a canonical HTTPS actuator health endpoint" >&2
        exit 64
    fi

    # The caller supplies each service's actual management-health URL. Gateway and identity use
    # private management listeners, while Task/Goal currently exposes only /actuator/health.
    wait_for_health "${service}" "${health_url}" 'Staging health for '

    printf '%s\n' "Staging health is UP for ${service}"
done
