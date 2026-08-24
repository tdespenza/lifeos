#!/usr/bin/env bash
set -euo pipefail

readonly GATEWAY_URL="${LIFEOS_E2E_GATEWAY_BASE_URL:-}"
readonly GATEWAY_MANAGEMENT_URL="${LIFEOS_E2E_GATEWAY_MANAGEMENT_BASE_URL:-}"
readonly IDENTITY_MANAGEMENT_URL="${LIFEOS_E2E_IDENTITY_MANAGEMENT_BASE_URL:-}"
readonly CORRELATION_ID="11111111-1111-4111-8111-111111111111"

if ! command -v curl >/dev/null 2>&1 \
    || ! command -v jq >/dev/null 2>&1 \
    || ! command -v rg >/dev/null 2>&1; then
    echo "curl, jq, and rg are required to run the end-to-end smoke test" >&2
    exit 69
fi

validate_url() {
    # Accept only deployment base URLs; endpoint paths are appended by the smoke test itself.
    local variable_name="$1"
    local value="$2"
    if [[ ! "${value}" =~ ^https://[^/?#]+(/[^?#]*)?$ ]]; then
        echo "${variable_name} must be a canonical HTTPS URL without query or fragment" >&2
        exit 64
    fi
}

validate_url LIFEOS_E2E_GATEWAY_BASE_URL "${GATEWAY_URL}"
validate_url LIFEOS_E2E_GATEWAY_MANAGEMENT_BASE_URL "${GATEWAY_MANAGEMENT_URL}"
validate_url LIFEOS_E2E_IDENTITY_MANAGEMENT_BASE_URL "${IDENTITY_MANAGEMENT_URL}"

assert_ready() {
    # Require an explicit UP readiness response before exercising the cross-service request path.
    local service_name="$1"
    local base_url="$2"
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
    printf '%s\n' "End-to-end prerequisite is ready: ${service_name}"
}

# Gateway's public API listener and the gateway/identity readiness listeners use different ports.
# Task/Goal is intentionally omitted until it exposes an independent readiness endpoint.
assert_ready gateway "${GATEWAY_MANAGEMENT_URL}"
assert_ready identity "${IDENTITY_MANAGEMENT_URL}"

headers_file="$(mktemp)"
body_file="$(mktemp)"
trap 'rm -f "${headers_file}" "${body_file}"' EXIT

# The invalid body is deliberate: it traverses Gateway -> Identity without creating a permanent
# test account, while asserting the public failure and correlation contracts of the live topology.
status_code="$(curl \
    --silent \
    --show-error \
    --location \
    --proto '=https' \
    --connect-timeout 10 \
    --max-time 20 \
    --header "X-Correlation-ID: ${CORRELATION_ID}" \
    --header 'Content-Type: application/json' \
    --data '{"email":"not-an-email","displayName":" "}' \
    --dump-header "${headers_file}" \
    --output "${body_file}" \
    --write-out '%{http_code}' \
    "${GATEWAY_URL%/}/api/v1/accounts")"

if [[ "${status_code}" != "400" ]]; then
    echo "Gateway-to-identity invalid-registration flow returned ${status_code}, expected 400" >&2
    exit 65
fi

if ! tr -d '\r' < "${headers_file}" | rg --ignore-case --quiet \
    "^x-correlation-id: ${CORRELATION_ID}$"; then
    echo "Gateway-to-identity flow did not preserve the canonical correlation ID" >&2
    exit 65
fi

printf '%s\n' "End-to-end gateway-to-identity contract passed"
