#!/usr/bin/env bash
set -euo pipefail

if ! command -v dirname >/dev/null 2>&1; then
    echo "dirname is required to resolve the repository root" >&2
    exit 69
fi

REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly REPOSITORY_ROOT
readonly IMAGE_REFERENCE_VALIDATION_SCRIPT="${REPOSITORY_ROOT}/scripts/image-reference-validation.sh"
if [[ ! -f "${IMAGE_REFERENCE_VALIDATION_SCRIPT}" || ! -r "${IMAGE_REFERENCE_VALIDATION_SCRIPT}" ]]; then
    echo "Image reference validation library is required" >&2
    exit 69
fi
# shellcheck disable=SC1090,SC1091
source "${IMAGE_REFERENCE_VALIDATION_SCRIPT}"
required_variables=(
    STAGING_DEPLOY_WEBHOOK_URL
    GITHUB_SHA
    GITHUB_REF_NAME
    GITHUB_REPOSITORY
    LIFEOS_IMAGE_PREFIX
    LIFEOS_IMAGE_TAG
)

if ! command -v jq >/dev/null 2>&1; then
    echo "jq is required to construct the staging deployment payload" >&2
    exit 69
fi

if ! command -v curl >/dev/null 2>&1; then
    echo "curl is required to send the staging deployment request" >&2
    exit 69
fi

for variable in "${required_variables[@]}"; do
    if [[ -z "${!variable:-}" ]]; then
        echo "${variable} is required for a staging deployment" >&2
        exit 64
    fi
done

if [[ ! "${STAGING_DEPLOY_WEBHOOK_URL}" =~ ^https://[^/@?#]+([/?][^#]*)?$ ]]; then
    echo "STAGING_DEPLOY_WEBHOOK_URL must use HTTPS" >&2
    exit 64
fi

if [[ ! "${GITHUB_SHA}" =~ ^[0-9a-f]{40}$ ]]; then
    echo "GITHUB_SHA must be a full lowercase Git commit SHA" >&2
    exit 64
fi

for service_discovery_command in find basename sort; do
    if ! command -v "${service_discovery_command}" >/dev/null 2>&1; then
        echo "${service_discovery_command} is required to discover service Dockerfiles" >&2
        exit 69
    fi
done

SERVICES=()
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

# Validate every image that the deployment endpoint will consume before assembling its payload.
# This keeps malformed registry paths, tags, and Dockerfile-derived service names out of the
# request boundary rather than delegating configuration errors to the staging deployment service.
validate_image_reference() {
    local image_reference="$1"
    local repository_name

    if [[ ! "${image_reference}" =~ ${IMAGE_REFERENCE_PATTERN} ]]; then
        printf 'Invalid container image reference %q generated from LIFEOS_IMAGE_PREFIX and LIFEOS_IMAGE_TAG\n' \
            "${image_reference}" >&2
        return 1
    fi

    # Tags always follow the final colon in a syntactically valid reference, so this preserves a
    # registry port. Docker's reference parser limits this entire name, including any registry,
    # rather than only its slash-separated path.
    repository_name="${image_reference%:*}"

    if (( ${#repository_name} > IMAGE_REPOSITORY_NAME_MAX_LENGTH )); then
        printf 'Invalid container image reference %q generated from LIFEOS_IMAGE_PREFIX and LIFEOS_IMAGE_TAG\n' \
            "${image_reference}" >&2
        return 1
    fi
}

for service in "${SERVICES[@]}"; do
    if ! validate_image_reference "${LIFEOS_IMAGE_PREFIX}/${service}:${LIFEOS_IMAGE_TAG}"; then
        exit 64
    fi
done

services_json="$(printf '%s\n' "${SERVICES[@]}" | jq --raw-input . | jq --slurp .)"

payload="$(jq -cn \
    --arg repository "${GITHUB_REPOSITORY}" \
    --arg ref "${GITHUB_REF_NAME}" \
    --arg sha "${GITHUB_SHA}" \
    --arg imagePrefix "${LIFEOS_IMAGE_PREFIX}" \
    --arg imageTag "${LIFEOS_IMAGE_TAG}" \
    --argjson services "${services_json}" \
    '{repository: $repository, ref: $ref, sha: $sha, imagePrefix: $imagePrefix, imageTag: $imageTag, services: $services}')"

# Do not print the webhook URL or its response: either can carry deployment credentials.
curl \
    --disable \
    --globoff \
    --fail \
    --silent \
    --show-error \
    --location \
    --max-redirs 0 \
    --proto '=https' \
    --connect-timeout 10 \
    --max-time 120 \
    --header 'Content-Type: application/json' \
    --data "${payload}" \
    --output /dev/null \
    "${STAGING_DEPLOY_WEBHOOK_URL}"

printf '%s\n' "Staging deployment endpoint accepted ${GITHUB_SHA} for ${#SERVICES[@]} service images"
