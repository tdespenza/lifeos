#!/usr/bin/env bash
set -euo pipefail

readonly GATEWAY_URL="${LIFEOS_E2E_GATEWAY_BASE_URL:-}"
readonly GATEWAY_MANAGEMENT_URL="${LIFEOS_E2E_GATEWAY_MANAGEMENT_BASE_URL:-}"
readonly IDENTITY_MANAGEMENT_URL="${LIFEOS_E2E_IDENTITY_MANAGEMENT_BASE_URL:-}"
readonly CORRELATION_ID="11111111-1111-4111-8111-111111111111"
readonly HEALTH_CHECK_MAX_ATTEMPTS=6
readonly HEALTH_CHECK_MAX_BACKOFF_SECONDS=16
readonly HEALTH_RESPONSE_MAX_BYTES=65536

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
    if [[ ! "${value}" =~ ^https://[^/@?#]+(/[^?#]*)?$ ]]; then
        echo "${variable_name} must be a canonical HTTPS URL without query or fragment" >&2
        exit 64
    fi
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

    if ! response_file="$(mktemp)"; then
        printf 'Unable to allocate a bounded health-response buffer for %s\n' "${service_name}" >&2
        return 1
    fi

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

headers_file="$(mktemp)"
trap 'rm -f "${headers_file}"' EXIT

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
