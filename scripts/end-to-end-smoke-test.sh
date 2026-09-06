#!/usr/bin/env bash
set -euo pipefail

readonly GATEWAY_URL="${LIFEOS_E2E_GATEWAY_BASE_URL:-}"
readonly GATEWAY_MANAGEMENT_URL="${LIFEOS_E2E_GATEWAY_MANAGEMENT_BASE_URL:-}"
readonly IDENTITY_MANAGEMENT_URL="${LIFEOS_E2E_IDENTITY_MANAGEMENT_BASE_URL:-}"
readonly CORRELATION_ID="11111111-1111-4111-8111-111111111111"
readonly SCRIPT_PATH="${BASH_SOURCE[0]}"
if [[ "${SCRIPT_PATH}" == */* ]]; then
    SCRIPT_DIRECTORY="${SCRIPT_PATH%/*}"
else
    SCRIPT_DIRECTORY="."
fi
if ! SCRIPT_DIRECTORY="$(cd -- "${SCRIPT_DIRECTORY}" && pwd -P)"; then
    echo "Unable to resolve the end-to-end smoke-test script directory" >&2
    exit 69
fi
readonly SCRIPT_DIRECTORY
readonly HTTPS_AUTHORITY_VALIDATION_SCRIPT="${SCRIPT_DIRECTORY}/https-authority-validation.sh"
if [[ ! -f "${HTTPS_AUTHORITY_VALIDATION_SCRIPT}" || ! -r "${HTTPS_AUTHORITY_VALIDATION_SCRIPT}" ]]; then
    echo "HTTPS authority validation library is required" >&2
    exit 69
fi
# The library path is derived from this script's directory and is checked above.
# shellcheck disable=SC1090,SC1091
source "${HTTPS_AUTHORITY_VALIDATION_SCRIPT}"
headers_file=""

cleanup_temporary_files() {
    local exit_status="$1"

    trap - EXIT HUP INT TERM
    if [[ -n "${HEALTH_RESPONSE_FILE}" ]]; then
        rm -f -- "${HEALTH_RESPONSE_FILE}" || true
    fi
    if [[ -n "${headers_file}" ]]; then
        rm -f -- "${headers_file}" || true
    fi
    exit "${exit_status}"
}
trap 'cleanup_temporary_files "$?"' EXIT
trap 'cleanup_temporary_files 129' HUP
trap 'cleanup_temporary_files 130' INT
trap 'cleanup_temporary_files 143' TERM

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

validate_url LIFEOS_E2E_GATEWAY_BASE_URL "${GATEWAY_URL}"
validate_url LIFEOS_E2E_GATEWAY_MANAGEMENT_BASE_URL "${GATEWAY_MANAGEMENT_URL}"
validate_url LIFEOS_E2E_IDENTITY_MANAGEMENT_BASE_URL "${IDENTITY_MANAGEMENT_URL}"

assert_ready() {
    # Require an explicit UP readiness response before exercising the cross-service request path.
    local service_name="$1"
    local base_url="$2"
    wait_for_health "${service_name}" "${base_url%/}/actuator/health/readiness" 'End-to-end prerequisite '
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
    "^x-correlation-id:[\t ]*${CORRELATION_ID}[\t ]*$"; then
    echo "Gateway-to-identity flow did not preserve the canonical correlation ID" >&2
    exit 65
fi

printf '%s\n' "End-to-end gateway-to-identity contract passed"
