#!/usr/bin/env bash

# This file is sourced by multiple operational entrypoints. Keep repeated sourcing idempotent so
# readonly constants are initialized only once in a caller's shell.
if [[ "${LIFEOS_HTTPS_AUTHORITY_VALIDATION_LOADED:-}" == "1" ]]; then
    if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
        exit 0
    fi
    return 0
fi
readonly LIFEOS_HTTPS_AUTHORITY_VALIDATION_LOADED=1

# Shared HTTPS authority validation and health-check retry helpers for operational smoke and
# chaos scripts. Callers retain endpoint-specific path policies and use
# has_valid_https_authority for the authority grammar.
readonly HTTPS_URL_STRUCTURE_PATTERN='^https://([^/?#]*)(/[^?#]*)?$'
readonly HTTPS_BRACKETED_AUTHORITY_PATTERN='^\[([^][]+)\](:([0-9]+))?$'
readonly HTTPS_UNBRACKETED_AUTHORITY_PATTERN='^([^:]+)(:([0-9]+))?$'
readonly HTTPS_DNS_HOST_PATTERN='^([A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)(\.([A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?))*$'
readonly HEALTH_CHECK_MAX_BACKOFF_SECONDS=16
readonly HEALTH_CHECK_MAX_ATTEMPTS=6
readonly HEALTH_RESPONSE_MAX_BYTES=65536
HEALTH_RESPONSE_FILE=""

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

is_legacy_ipv4_component_sequence() {
    local value="$1"
    local component
    local IFS='.'
    local -a components

    read -r -a components <<< "${value}"
    if (( ${#components[@]} < 1 || ${#components[@]} > 4 )); then
        return 1
    fi

    # Reject any one-to-four-component decimal, octal, or hexadecimal legacy numeric candidate
    # even when it overflows IPv4. libc and curl parsing rules vary by version; allowing an
    # out-of-range candidate to fall through as a DNS name would make this boundary
    # environment-dependent.
    for component in "${components[@]}"; do
        if [[ ! "${component}" =~ ^0[xX][0-9A-Fa-f]+$ && ! "${component}" =~ ^[0-9]+$ ]]; then
            return 1
        fi
    done
    return 0
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
        # An IPv4 tail consumes the final two hextets of the complete literal. A tail on the
        # left of :: would leave later IPv6 groups, so reject it before counting compressed
        # sections independently.
        if [[ "${left_section}" == *.* ]]; then
            return 1
        fi
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
    if is_legacy_ipv4_component_sequence "${host}"; then
        return 1
    fi
    [[ "${host}" =~ ${HTTPS_DNS_HOST_PATTERN} ]]
}

health_check_delay_seconds() {
    local attempt="$1"
    local backoff_seconds=$((1 << (attempt - 1)))

    if (( backoff_seconds > HEALTH_CHECK_MAX_BACKOFF_SECONDS )); then
        backoff_seconds="${HEALTH_CHECK_MAX_BACKOFF_SECONDS}"
    fi

    # Keep jitter positive so a failed health request cannot be immediately reissued.
    printf '%s\n' "$((1 + RANDOM % backoff_seconds))"
}

wait_for_health() {
    local service_name="$1"
    local health_url="$2"
    local log_prefix="$3"
    local attempt delay_seconds response_file response_size

    if ! HEALTH_RESPONSE_FILE="$(mktemp)"; then
        printf 'Unable to allocate a bounded health-response buffer for %s\n' "${service_name}" >&2
        return 1
    fi
    response_file="${HEALTH_RESPONSE_FILE}"

    # Capture one sentinel byte beyond the cap before jq parses the response. This fails closed
    # for unknown-length/chunked bodies while bounding temporary storage and jq input.
    # Retry the complete probe so curl only handles transport failures while jq also handles a
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
        printf '%s%s is not UP; retrying in %ss (attempt %s/%s)\n' \
            "${log_prefix}" "${service_name}" "${delay_seconds}" "${attempt}" "${HEALTH_CHECK_MAX_ATTEMPTS}" >&2
        sleep "${delay_seconds}"
    done

    if ! rm -f "${response_file}"; then
        printf 'Unable to remove the bounded health-response buffer for %s\n' "${service_name}" >&2
        return 1
    fi
    HEALTH_RESPONSE_FILE=""

    printf '%s%s did not report UP after %s attempts\n' \
        "${log_prefix}" "${service_name}" "${HEALTH_CHECK_MAX_ATTEMPTS}" >&2
    return 1
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
        # RFC 4291's longest textual IPv6 form (including an IPv4 tail) is 45 characters.
        # Reject oversized literals before regex splitting to keep validation bounded.
        if (( ${#host} > 45 )); then
            return 1
        fi
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
