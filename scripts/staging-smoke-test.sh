#!/usr/bin/env bash
set -euo pipefail

if ! command -v dirname >/dev/null 2>&1; then
    echo "dirname is required to resolve the repository root" >&2
    exit 69
fi

REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly REPOSITORY_ROOT
readonly SERVICE_HEALTH_URLS_JSON="${STAGING_SERVICE_HEALTH_URLS_JSON:-}"
readonly HEALTH_CHECK_MAX_ATTEMPTS=6
readonly HEALTH_CHECK_MAX_BACKOFF_SECONDS=16
readonly HEALTH_RESPONSE_MAX_BYTES=65536
SERVICES=()

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

health_check_delay_seconds() {
    local attempt="$1"
    local backoff_seconds=$((1 << (attempt - 1)))

    if (( backoff_seconds > HEALTH_CHECK_MAX_BACKOFF_SECONDS )); then
        backoff_seconds="${HEALTH_CHECK_MAX_BACKOFF_SECONDS}"
    fi

    # Full jitter avoids synchronizing retries from independently deployed services.
    printf '%s\n' "$((RANDOM % (backoff_seconds + 1)))"
}

wait_for_health() {
    local service="$1"
    local health_url="$2"
    local attempt delay_seconds response_file response_size

    if ! response_file="$(mktemp)"; then
        printf 'Unable to allocate a bounded health-response buffer for %s\n' "${service}" >&2
        return 1
    fi

    # Capture one sentinel byte beyond the cap before jq parses the response. This fails closed
    # for unknown-length/chunked bodies while bounding temporary storage and jq input.
    # Retry the complete probe because curl only retries transport failures; jq can reject a
    # successfully returned health payload whose application status is still DOWN.
    for ((attempt = 1; attempt <= HEALTH_CHECK_MAX_ATTEMPTS; attempt++)); do
        if curl \
            --disable \
            --fail \
            --silent \
            --show-error \
            --location \
            --proto '=https' \
            --connect-timeout 10 \
            --max-time 20 \
            "${health_url}" \
            | head -c "$((HEALTH_RESPONSE_MAX_BYTES + 1))" > "${response_file}" \
            && response_size="$(wc -c < "${response_file}")" \
            && [[ "${response_size}" =~ ^[[:space:]]*([0-9]+)[[:space:]]*$ ]] \
            && (( 10#${BASH_REMATCH[1]} <= HEALTH_RESPONSE_MAX_BYTES )) \
            && jq --exit-status '.status == "UP"' < "${response_file}" >/dev/null; then
            if ! rm -f "${response_file}"; then
                printf 'Unable to remove the bounded health-response buffer for %s\n' "${service}" >&2
                return 1
            fi
            return 0
        fi

        if (( attempt == HEALTH_CHECK_MAX_ATTEMPTS )); then
            break
        fi

        delay_seconds="$(health_check_delay_seconds "${attempt}")"
        printf 'Staging health for %s is not UP; retrying in %ss (attempt %s/%s)\n' \
            "${service}" "${delay_seconds}" "${attempt}" "${HEALTH_CHECK_MAX_ATTEMPTS}" >&2
        sleep "${delay_seconds}"
    done

    if ! rm -f "${response_file}"; then
        printf 'Unable to remove the bounded health-response buffer for %s\n' "${service}" >&2
        return 1
    fi

    printf 'Staging health for %s did not report UP after %s attempts\n' \
        "${service}" "${HEALTH_CHECK_MAX_ATTEMPTS}" >&2
    return 1
}

for service in "${SERVICES[@]}"; do
    if ! health_url="$(jq --raw-output --exit-status --arg service "${service}" \
        '.[$service] // empty' <<< "${SERVICE_HEALTH_URLS_JSON}")" || [[ -z "${health_url}" ]]; then
        echo "STAGING_SERVICE_HEALTH_URLS_JSON is missing an HTTPS actuator health URL for ${service}" >&2
        exit 64
    fi

    if [[ ! "${health_url}" =~ ^https://[^/@?#]+(/[^?#]*)?/actuator/health(/(readiness|liveness))?$ ]]; then
        echo "Staging health URL for ${service} must be a canonical HTTPS actuator health endpoint" >&2
        exit 64
    fi

    # The caller supplies each service's actual management-health URL. Gateway and identity use
    # private management listeners, while Task/Goal currently exposes only /actuator/health.
    wait_for_health "${service}" "${health_url}"

    printf '%s\n' "Staging health is UP for ${service}"
done
