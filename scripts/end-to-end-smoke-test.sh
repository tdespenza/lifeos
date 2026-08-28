#!/usr/bin/env bash
set -euo pipefail

readonly GATEWAY_URL="${LIFEOS_E2E_GATEWAY_BASE_URL:-}"
readonly GATEWAY_MANAGEMENT_URL="${LIFEOS_E2E_GATEWAY_MANAGEMENT_BASE_URL:-}"
readonly IDENTITY_MANAGEMENT_URL="${LIFEOS_E2E_IDENTITY_MANAGEMENT_BASE_URL:-}"
readonly CORRELATION_ID="11111111-1111-4111-8111-111111111111"
readonly HEALTH_CHECK_MAX_ATTEMPTS=6
readonly HEALTH_CHECK_MAX_BACKOFF_SECONDS=16
readonly HEALTH_RESPONSE_MAX_BYTES=65536
# Keep the path policy separate from the authority policy so endpoint-specific validation can
# retain its existing path contract while rejecting invalid hosts and ports before curl runs.
readonly HTTPS_URL_STRUCTURE_PATTERN='^https://([^/?#]*)(/[^?#]*)?$'
readonly HTTPS_BRACKETED_AUTHORITY_PATTERN='^\[([^][]+)\](:([0-9]+))?$'
readonly HTTPS_UNBRACKETED_AUTHORITY_PATTERN='^([^:]+)(:([0-9]+))?$'
readonly HTTPS_DNS_HOST_PATTERN='^([A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)(\.([A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?))*$'
HEALTH_RESPONSE_FILE=""
headers_file=""

cleanup_temporary_files() {
    local exit_status=$?

    trap - EXIT
    if [[ -n "${HEALTH_RESPONSE_FILE}" ]]; then
        rm -f -- "${HEALTH_RESPONSE_FILE}" || true
    fi
    if [[ -n "${headers_file}" ]]; then
        rm -f -- "${headers_file}" || true
    fi
    exit "${exit_status}"
}
trap cleanup_temporary_files EXIT

if ! command -v curl >/dev/null 2>&1 \
    || ! command -v head >/dev/null 2>&1 \
    || ! command -v jq >/dev/null 2>&1 \
    || ! command -v rg >/dev/null 2>&1 \
    || ! command -v sleep >/dev/null 2>&1 \
    || ! command -v mktemp >/dev/null 2>&1 \
    || ! command -v tr >/dev/null 2>&1 \
    || ! command -v rm >/dev/null 2>&1 \
    || ! command -v wc >/dev/null 2>&1; then
    echo "curl, head, jq, rg, sleep, mktemp, tr, rm, and wc are required to run the end-to-end smoke test" >&2
    exit 69
fi

validate_url() {
    # Accept only deployment base URLs; endpoint paths are appended by the smoke test itself.
    local variable_name="$1"
    local value="$2"
    if [[ ! "${value}" =~ ^https://[^/@?#]+(/[^?#]*)?$ ]] \
        || ! has_valid_https_authority "${value}"; then
        echo "${variable_name} must be a canonical HTTPS URL without query or fragment" >&2
        exit 64
    fi
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

validate_url LIFEOS_E2E_GATEWAY_BASE_URL "${GATEWAY_URL}"
validate_url LIFEOS_E2E_GATEWAY_MANAGEMENT_BASE_URL "${GATEWAY_MANAGEMENT_URL}"
validate_url LIFEOS_E2E_IDENTITY_MANAGEMENT_BASE_URL "${IDENTITY_MANAGEMENT_URL}"

health_check_delay_seconds() {
    local attempt="$1"
    local backoff_seconds=$((1 << (attempt - 1)))

    if (( backoff_seconds > HEALTH_CHECK_MAX_BACKOFF_SECONDS )); then
        backoff_seconds="${HEALTH_CHECK_MAX_BACKOFF_SECONDS}"
    fi

    # Full jitter avoids synchronizing retries across gateway and identity readiness probes.
    printf '%s\n' "$((RANDOM % (backoff_seconds + 1)))"
}

wait_for_health() {
    local service_name="$1"
    local health_url="$2"
    local attempt delay_seconds response_file response_size

    if ! HEALTH_RESPONSE_FILE="$(mktemp)"; then
        printf 'Unable to allocate a bounded health-response buffer for %s\n' "${service_name}" >&2
        return 1
    fi
    response_file="${HEALTH_RESPONSE_FILE}"

    # Capture one sentinel byte beyond the cap before jq parses the response. This fails closed
    # for unknown-length/chunked bodies while bounding temporary storage and jq input.
    # Retry the whole health assertion: curl does not retry when jq rejects a 200/DOWN payload.
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
                printf 'Unable to remove the bounded health-response buffer for %s\n' "${service_name}" >&2
                return 1
            fi
            HEALTH_RESPONSE_FILE=""
            return 0
        fi

        if (( attempt == HEALTH_CHECK_MAX_ATTEMPTS )); then
            break
        fi

        delay_seconds="$(health_check_delay_seconds "${attempt}")"
        printf 'End-to-end prerequisite %s is not UP; retrying in %ss (attempt %s/%s)\n' \
            "${service_name}" "${delay_seconds}" "${attempt}" "${HEALTH_CHECK_MAX_ATTEMPTS}" >&2
        sleep "${delay_seconds}"
    done

    if ! rm -f "${response_file}"; then
        printf 'Unable to remove the bounded health-response buffer for %s\n' "${service_name}" >&2
        return 1
    fi
    HEALTH_RESPONSE_FILE=""

    printf 'End-to-end prerequisite %s did not report UP after %s attempts\n' \
        "${service_name}" "${HEALTH_CHECK_MAX_ATTEMPTS}" >&2
    return 1
}

assert_ready() {
    # Require an explicit UP readiness response before exercising the cross-service request path.
    local service_name="$1"
    local base_url="$2"
    wait_for_health "${service_name}" "${base_url%/}/actuator/health/readiness"
    printf '%s\n' "End-to-end prerequisite is ready: ${service_name}"
}

# Gateway's public API listener and the gateway/identity readiness listeners use different ports.
# Task/Goal is intentionally omitted until it exposes an independent readiness endpoint.
assert_ready gateway "${GATEWAY_MANAGEMENT_URL}"
assert_ready identity "${IDENTITY_MANAGEMENT_URL}"

if ! headers_file="$(mktemp)"; then
    printf '%s\n' 'Unable to allocate a temporary response-header buffer for the gateway-to-identity contract' >&2
    exit 1
fi

# The invalid body is deliberate: it traverses Gateway -> Identity without creating a permanent
# test account, while asserting the public failure and correlation contracts of the live topology.
status_code="$(curl \
    --disable \
    --silent \
    --show-error \
    --location \
    --max-redirs 0 \
    --proto '=https' \
    --connect-timeout 10 \
    --max-time 20 \
    --header "X-Correlation-ID: ${CORRELATION_ID}" \
    --header 'Content-Type: application/json' \
    --data '{"email":"not-an-email","displayName":" "}' \
    --dump-header "${headers_file}" \
    --output /dev/null \
    --write-out '%{http_code}' \
    "${GATEWAY_URL%/}/api/v1/accounts")"

if [[ "${status_code}" != "400" ]]; then
    echo "Gateway-to-identity invalid-registration flow returned ${status_code}, expected 400" >&2
    exit 65
fi

if ! tr -d '\r' < "${headers_file}" | rg --ignore-case --quiet \
    "^x-correlation-id:[\t ]*${CORRELATION_ID}$"; then
    echo "Gateway-to-identity flow did not preserve the canonical correlation ID" >&2
    exit 65
fi

printf '%s\n' "End-to-end gateway-to-identity contract passed"
