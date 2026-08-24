#!/usr/bin/env bash
set -euo pipefail

REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly REPOSITORY_ROOT
readonly SERVICE_HEALTH_URLS_JSON="${STAGING_SERVICE_HEALTH_URLS_JSON:-}"
SERVICES=()

if ! command -v curl >/dev/null 2>&1 || ! command -v jq >/dev/null 2>&1; then
    echo "curl and jq are required to run the staging smoke test" >&2
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

while IFS= read -r service; do
    SERVICES+=("${service}")
done < <(find "${REPOSITORY_ROOT}/infrastructure/docker" -maxdepth 1 -type f -name '*.Dockerfile' \
    -exec basename {} .Dockerfile \; | sort)

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

    if [[ ! "${health_url}" =~ ^https://[^/?#]+(/[^?#]*)?/actuator/health(/(readiness|liveness))?$ ]]; then
        echo "Staging health URL for ${service} must be a canonical HTTPS actuator health endpoint" >&2
        exit 64
    fi

    # The caller supplies each service's actual management-health URL. Gateway and identity use
    # private management listeners, while Task/Goal currently exposes only /actuator/health.
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
        "${health_url}" \
        | jq --exit-status '.status == "UP"' >/dev/null

    printf '%s\n' "Staging health is UP for ${service}"
done
