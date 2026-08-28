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
# Keep the path policy separate from the authority policy so endpoint-specific validation can
# retain its existing path contract while rejecting invalid hosts and ports before curl runs.
readonly HTTPS_URL_STRUCTURE_PATTERN='^https://([^/?#]*)(/[^?#]*)?$'
readonly HTTPS_BRACKETED_AUTHORITY_PATTERN='^\[([^][]+)\](:([0-9]+))?$'
readonly HTTPS_UNBRACKETED_AUTHORITY_PATTERN='^([^:]+)(:([0-9]+))?$'
readonly HTTPS_DNS_HOST_PATTERN='^([A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)(\.([A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?))*$'
SERVICES=()
HEALTH_RESPONSE_FILE=""

cleanup_health_response_file() {
    local exit_status=$?

    trap - EXIT
    if [[ -n "${HEALTH_RESPONSE_FILE}" ]]; then
        rm -f -- "${HEALTH_RESPONSE_FILE}" || true
    fi
    exit "${exit_status}"
}
trap cleanup_health_response_file EXIT

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

is_valid_ipv4_literal() {
    local value="$1"
    local octet
    local index

    if [[ ! "${value}" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]; then
        return 1
    fi

    for ((index = 1; index <= 4; index++)); do
        octet="${BASH_REMATCH[${index}]}"
        if (( ${#octet} > 3 )) \
            || [[ "${octet}" != "0" && "${octet}" == 0* ]] \
            || (( 10#${octet} > 255 )); then
            return 1
        fi
    done
}

count_ipv6_section_hextets() {
    local section="$1"
    local group
    local index
    local -a groups

    IPV6_SECTION_HEXTET_COUNT=0
    if [[ -z "${section}" ]]; then
        return 0
    fi
    if [[ "${section}" == :* || "${section}" == *: ]]; then
        return 1
    fi

    local IFS=':'
    read -r -a groups <<< "${section}"
    for ((index = 0; index < ${#groups[@]}; index++)); do
        group="${groups[${index}]}"
        if [[ "${group}" == *.* ]]; then
            if (( index != ${#groups[@]} - 1 )) || ! is_valid_ipv4_literal "${group}"; then
                return 1
            fi
            IPV6_SECTION_HEXTET_COUNT=$((IPV6_SECTION_HEXTET_COUNT + 2))
        elif [[ "${group}" =~ ^[0-9A-Fa-f]{1,4}$ ]]; then
            IPV6_SECTION_HEXTET_COUNT=$((IPV6_SECTION_HEXTET_COUNT + 1))
        else
            return 1
        fi
    done
}

is_valid_ipv6_literal() {
    local literal="$1"
    local compressed_literal
    local left_section right_section
    local left_hextets right_hextets

    if [[ ! "${literal}" =~ ^[0-9A-Fa-f:.]+$ ]]; then
        return 1
    fi

    if [[ "${literal}" == *"::"* ]]; then
        compressed_literal="${literal/::/@}"
        if [[ "${compressed_literal}" == *"::"* ]]; then
            return 1
        fi
        left_section="${compressed_literal%%@*}"
        right_section="${compressed_literal#*@}"
        if ! count_ipv6_section_hextets "${left_section}"; then
            return 1
        fi
        left_hextets="${IPV6_SECTION_HEXTET_COUNT}"
        if ! count_ipv6_section_hextets "${right_section}"; then
            return 1
        fi
        right_hextets="${IPV6_SECTION_HEXTET_COUNT}"

        # A double colon must replace at least one otherwise omitted hextet.
        (( left_hextets + right_hextets < 8 ))
        return
    fi

    if ! count_ipv6_section_hextets "${literal}"; then
        return 1
    fi
    (( IPV6_SECTION_HEXTET_COUNT == 8 ))
}

is_valid_dns_or_ipv4_host() {
    local host="$1"

    # Deployment endpoints use canonical ASCII DNS names or IPv4 literals. This rejects
    # whitespace, userinfo-like delimiters, empty labels, and non-canonical numeric literals
    # before curl can treat a configuration error as a retryable transport failure.
    if (( ${#host} > 253 )); then
        return 1
    fi
    if [[ "${host}" == *.* && "${host}" =~ ^[0-9.]+$ ]]; then
        is_valid_ipv4_literal "${host}"
        return
    fi
    [[ "${host}" =~ ${HTTPS_DNS_HOST_PATTERN} ]]
}

has_valid_https_authority() {
    local value="$1"
    local authority host port

    if [[ ! "${value}" =~ ${HTTPS_URL_STRUCTURE_PATTERN} ]]; then
        return 1
    fi
    authority="${BASH_REMATCH[1]}"

    if [[ "${authority}" == \[* ]]; then
        if [[ ! "${authority}" =~ ${HTTPS_BRACKETED_AUTHORITY_PATTERN} ]]; then
            return 1
        fi
        host="${BASH_REMATCH[1]}"
        port="${BASH_REMATCH[3]:-}"
        if ! is_valid_ipv6_literal "${host}"; then
            return 1
        fi
    else
        if [[ ! "${authority}" =~ ${HTTPS_UNBRACKETED_AUTHORITY_PATTERN} ]]; then
            return 1
        fi
        host="${BASH_REMATCH[1]}"
        port="${BASH_REMATCH[3]:-}"
        if ! is_valid_dns_or_ipv4_host "${host}"; then
            return 1
        fi
    fi

    if [[ -z "${port}" ]]; then
        return 0
    fi
    # A URL port is decimal TCP port 1 through 65535. Check its length before arithmetic so a
    # malformed, arbitrarily long environment value cannot overflow Bash's integer conversion.
    if (( ${#port} > 5 )); then
        return 1
    fi
    (( 10#${port} >= 1 && 10#${port} <= 65535 ))
}

wait_for_health() {
    local service="$1"
    local health_url="$2"
    local attempt delay_seconds response_file response_size

    if ! HEALTH_RESPONSE_FILE="$(mktemp)"; then
        printf 'Unable to allocate a bounded health-response buffer for %s\n' "${service}" >&2
        return 1
    fi
    response_file="${HEALTH_RESPONSE_FILE}"

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
            --max-redirs 0 \
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
            HEALTH_RESPONSE_FILE=""
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
    HEALTH_RESPONSE_FILE=""

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

    if [[ ! "${health_url}" =~ ^https://[^/@?#]+(/[^?#]*)?/actuator/health(/(readiness|liveness))?$ ]] \
        || ! has_valid_https_authority "${health_url}"; then
        echo "Staging health URL for ${service} must be a canonical HTTPS actuator health endpoint" >&2
        exit 64
    fi

    # The caller supplies each service's actual management-health URL. Gateway and identity use
    # private management listeners, while Task/Goal currently exposes only /actuator/health.
    wait_for_health "${service}" "${health_url}"

    printf '%s\n' "Staging health is UP for ${service}"
done
