#!/usr/bin/env bash
set -euo pipefail

REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly REPOSITORY_ROOT
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

for variable in "${required_variables[@]}"; do
    if [[ -z "${!variable:-}" ]]; then
        echo "${variable} is required for a staging deployment" >&2
        exit 64
    fi
done

if [[ ! "${STAGING_DEPLOY_WEBHOOK_URL}" =~ ^https:// ]]; then
    echo "STAGING_DEPLOY_WEBHOOK_URL must use HTTPS" >&2
    exit 64
fi

if [[ ! "${GITHUB_SHA}" =~ ^[0-9a-f]{40}$ ]]; then
    echo "GITHUB_SHA must be a full lowercase Git commit SHA" >&2
    exit 64
fi

SERVICES=()
while IFS= read -r service; do
    SERVICES+=("${service}")
done < <(find "${REPOSITORY_ROOT}/infrastructure/docker" -maxdepth 1 -type f -name '*.Dockerfile' \
    -exec basename {} .Dockerfile \; | sort)

if [[ "${#SERVICES[@]}" -eq 0 ]]; then
    echo "No service Dockerfiles found in infrastructure/docker" >&2
    exit 66
fi

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
