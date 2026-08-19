#!/usr/bin/env bash
set -euo pipefail

readonly REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly SERVICE_URLS_JSON="${STAGING_SERVICE_URLS_JSON:-}"
SERVICES=()

if ! command -v curl >/dev/null 2>&1 || ! command -v jq >/dev/null 2>&1; then
    echo "curl and jq are required to run the staging smoke test" >&2
    exit 69
fi

if [[ -z "${SERVICE_URLS_JSON}" ]]; then
    echo "STAGING_SERVICE_URLS_JSON is required for the staging smoke test" >&2
    exit 64
fi

if ! jq --exit-status 'type == "object" and all(.[]; type == "string")' \
    <<< "${SERVICE_URLS_JSON}" >/dev/null; then
    echo "STAGING_SERVICE_URLS_JSON must be a JSON object mapping service names to HTTPS base URLs" >&2
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
    if ! base_url="$(jq --raw-output --exit-status --arg service "${service}" \
        '.[$service] // empty' <<< "${SERVICE_URLS_JSON}")" || [[ -z "${base_url}" ]]; then
        echo "STAGING_SERVICE_URLS_JSON is missing an HTTPS base URL for ${service}" >&2
        exit 64
    fi

    if [[ ! "${base_url}" =~ ^https://[^/?#]+(/[^?#]*)?$ ]]; then
        echo "Staging URL for ${service} must be a canonical HTTPS URL without query or fragment" >&2
        exit 64
    fi

    # Each Spring Boot service publishes its management listener separately. A healthy response
    # proves the rollout reached a ready process without exposing authenticated business data.
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
        "${base_url%/}/actuator/health/readiness" \
        | jq --exit-status '.status == "UP"' >/dev/null

    printf '%s\n' "Staging readiness is UP for ${service}"
done
