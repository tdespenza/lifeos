#!/usr/bin/env bash
set -euo pipefail

readonly REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly IMAGE_PREFIX="${LIFEOS_IMAGE_PREFIX:-lifeos}"
readonly IMAGE_TAG="${LIFEOS_IMAGE_TAG:-local}"
# Keep the scanner itself immutable; a mutable scanner tag would make the security gate
# non-reproducible and could change its policy behavior between otherwise identical builds.
readonly TRIVY_IMAGE="${LIFEOS_TRIVY_IMAGE:-aquasec/trivy:0.67.0@sha256:94711c60051c6cab848a292e3a67f62623fcee361b2bb661f43b17184f4afdac}"
# Share a cache per runner/local machine without placing it in the repository or Docker context.
readonly TRIVY_CACHE_DIR="${LIFEOS_TRIVY_CACHE_DIR:-${RUNNER_TEMP:-/tmp}/lifeos-trivy-cache}"
SERVICES=()

while IFS= read -r service; do
    SERVICES+=("${service}")
done < <(find "${REPOSITORY_ROOT}/infrastructure/docker" -maxdepth 1 -type f -name '*.Dockerfile' \
    -exec basename {} .Dockerfile \; | sort)
readonly SERVICES

if ! command -v docker >/dev/null 2>&1; then
    echo "docker is required to run the Trivy container scan" >&2
    exit 69
fi

if [[ "${#SERVICES[@]}" -eq 0 ]]; then
    echo "No service Dockerfiles found in infrastructure/docker" >&2
    exit 66
fi

mkdir -p "${TRIVY_CACHE_DIR}"

for service in "${SERVICES[@]}"; do
    image="${IMAGE_PREFIX}/${service}:${IMAGE_TAG}"
    if ! docker image inspect "${image}" >/dev/null 2>&1; then
        echo "Container image ${image} is missing; run scripts/build-container-images.sh first" >&2
        exit 66
    fi

    docker run --rm \
        --volume "${TRIVY_CACHE_DIR}:/root/.cache" \
        --volume /var/run/docker.sock:/var/run/docker.sock \
        "${TRIVY_IMAGE}" \
        image \
        --no-progress \
        --exit-code 1 \
        --ignore-unfixed \
        --severity HIGH,CRITICAL \
        "${image}"
done
