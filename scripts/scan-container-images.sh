#!/usr/bin/env bash
set -euo pipefail

REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly REPOSITORY_ROOT
readonly IMAGE_PREFIX="${LIFEOS_IMAGE_PREFIX:-lifeos}"
readonly IMAGE_TAG="${LIFEOS_IMAGE_TAG:-local}"
# Keep the scanner itself immutable; a mutable scanner tag would make the security gate
# non-reproducible and could change its policy behavior between otherwise identical builds.
readonly TRIVY_IMAGE="${LIFEOS_TRIVY_IMAGE:-aquasec/trivy:0.67.0@sha256:94711c60051c6cab848a292e3a67f62623fcee361b2bb661f43b17184f4afdac}"
# Share a cache per runner/local machine without placing it in the repository or Docker context.
readonly TRIVY_CACHE_DIR="${LIFEOS_TRIVY_CACHE_DIR:-${RUNNER_TEMP:-/tmp}/lifeos-trivy-cache}"
readonly IMAGE_NAME_COMPONENT_PATTERN='[a-z0-9]+(([._]|__|-+)[a-z0-9]+)*'
readonly IMAGE_REGISTRY_HOST_COMPONENT_PATTERN='[a-z0-9]([a-z0-9-]*[a-z0-9])?'
# Bracketed IPv6 registry hosts need full IPv6 parsing to distinguish malformed values such as
# "[aaaa]". Until that parser is available, accept only DNS-style registry hosts rather than
# allowing an invalid generated reference to reach Docker.
readonly IMAGE_REGISTRY_HOST_PATTERN="${IMAGE_REGISTRY_HOST_COMPONENT_PATTERN}(\.${IMAGE_REGISTRY_HOST_COMPONENT_PATTERN})*"
readonly IMAGE_TAG_PATTERN='[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}'
readonly IMAGE_REFERENCE_PATTERN="^(((${IMAGE_REGISTRY_HOST_PATTERN})(:[0-9]+)?)/)?${IMAGE_NAME_COMPONENT_PATTERN}(/${IMAGE_NAME_COMPONENT_PATTERN})*:${IMAGE_TAG_PATTERN}$"
SERVICES=()

while IFS= read -r service; do
    SERVICES+=("${service}")
done < <(find "${REPOSITORY_ROOT}/infrastructure/docker" -maxdepth 1 -type f -name '*.Dockerfile' \
    -exec basename {} .Dockerfile \; | sort)
readonly SERVICES

# Validate the fully assembled reference before inspect so parser errors are reported as invalid
# configuration rather than being confused with a locally missing image.
validate_image_reference() {
    local image_reference="$1"

    if [[ ! "${image_reference}" =~ ${IMAGE_REFERENCE_PATTERN} ]]; then
        printf 'Invalid container image reference %q generated from LIFEOS_IMAGE_PREFIX and LIFEOS_IMAGE_TAG\n' \
            "${image_reference}" >&2
        return 1
    fi
}

if ! command -v docker >/dev/null 2>&1; then
    echo "docker is required to run the Trivy container scan" >&2
    exit 69
fi

if [[ "${#SERVICES[@]}" -eq 0 ]]; then
    echo "No service Dockerfiles found in infrastructure/docker" >&2
    exit 66
fi

for service in "${SERVICES[@]}"; do
    if ! validate_image_reference "${IMAGE_PREFIX}/${service}:${IMAGE_TAG}"; then
        exit 64
    fi
done

if ! docker info >/dev/null 2>&1; then
    echo "Docker daemon is unavailable or inaccessible; start Docker and verify access before scanning images" >&2
    exit 69
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
