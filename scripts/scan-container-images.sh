#!/usr/bin/env bash
set -euo pipefail

if ! command -v dirname >/dev/null 2>&1; then
    echo "dirname is required to resolve the repository root" >&2
    exit 69
fi

REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly REPOSITORY_ROOT
readonly IMAGE_PREFIX="${LIFEOS_IMAGE_PREFIX:-lifeos}"
readonly IMAGE_TAG="${LIFEOS_IMAGE_TAG:-local}"
# Keep the scanner itself immutable. This container receives the Docker socket, so accepting an
# environment-provided image would let an untrusted image control the Docker daemon.
readonly TRIVY_IMAGE="aquasec/trivy:0.67.0@sha256:94711c60051c6cab848a292e3a67f62623fcee361b2bb661f43b17184f4afdac"
# Share a cache per runner/local machine without placing it in the repository or Docker context.
readonly TRIVY_CACHE_DIR="${LIFEOS_TRIVY_CACHE_DIR:-${RUNNER_TEMP:-/tmp}/lifeos-trivy-cache}"
# Trivy's filesystem cache uses an exclusive BoltDB lock. Coordinate every LifeOS scan that uses
# this cache so image and source scans retain warm-cache behavior without racing the database.
readonly TRIVY_CACHE_LOCK_DIRECTORY="${TRIVY_CACHE_DIR}/.lifeos-trivy-cache.lock"
readonly TRIVY_CACHE_LOCK_TIMEOUT_SECONDS=300
readonly TRIVY_CACHE_LOCK_POLL_SECONDS=1
# Docker can hang while connecting to its daemon, pulling the scanner image, or streaming a
# scan. Keep every invocation within a single, deliberately bounded operator-configurable
# deadline so a broken Docker dependency cannot consume the enclosing CI-job timeout.
readonly DOCKER_OPERATION_TIMEOUT_SECONDS="${LIFEOS_DOCKER_TIMEOUT_SECONDS:-300}"
readonly DOCKER_TIMEOUT_EXIT_STATUS=124
readonly IMAGE_NAME_COMPONENT_PATTERN='[a-z0-9]+(([._]|__|-+)[a-z0-9]+)*'
readonly IMAGE_REGISTRY_HOST_COMPONENT_PATTERN='[a-z0-9]([a-z0-9-]*[a-z0-9])?'
# Bracketed IPv6 registry hosts need full IPv6 parsing to distinguish malformed values such as
# "[aaaa]". Until that parser is available, accept only DNS-style registry hosts rather than
# allowing an invalid generated reference to reach Docker.
readonly IMAGE_REGISTRY_HOST_PATTERN="${IMAGE_REGISTRY_HOST_COMPONENT_PATTERN}(\.${IMAGE_REGISTRY_HOST_COMPONENT_PATTERN})*"
readonly IMAGE_TAG_PATTERN='[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}'
readonly IMAGE_REFERENCE_PATTERN="^(((${IMAGE_REGISTRY_HOST_PATTERN})(:[0-9]+)?)/)?${IMAGE_NAME_COMPONENT_PATTERN}(/${IMAGE_NAME_COMPONENT_PATTERN})*:${IMAGE_TAG_PATTERN}$"
# The Distribution reference parser limits the complete repository name (including an optional
# registry and port, but excluding the tag) to 255 characters.
readonly IMAGE_REPOSITORY_NAME_MAX_LENGTH=255
SERVICES=()

if [[ ! "${DOCKER_OPERATION_TIMEOUT_SECONDS}" =~ ^[1-9][0-9]{0,2}$ ]] \
    || (( 10#${DOCKER_OPERATION_TIMEOUT_SECONDS} > 900 )); then
    echo "LIFEOS_DOCKER_TIMEOUT_SECONDS must be between 1 and 900 seconds" >&2
    exit 64
fi

# A relative source can be interpreted as a Docker-managed named volume instead of the directory
# protected by this process's cache lock. Keep the lock and the scanner on one explicit host path.
if [[ "${TRIVY_CACHE_DIR}" != /* ]]; then
    echo "LIFEOS_TRIVY_CACHE_DIR must be an absolute path" >&2
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
readonly SERVICES

# Validate the fully assembled reference before inspect so parser errors are reported as invalid
# configuration rather than being confused with a locally missing image.
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

if ! command -v docker >/dev/null 2>&1; then
    echo "docker is required to run the Trivy container scan" >&2
    exit 69
fi

if command -v timeout >/dev/null 2>&1; then
    DOCKER_TIMEOUT_COMMAND="timeout"
elif command -v gtimeout >/dev/null 2>&1; then
    # macOS ships no timeout utility; Homebrew's coreutils exposes the GNU-compatible command
    # as gtimeout. Prefer timeout on CI/Linux while retaining a clear local development path.
    DOCKER_TIMEOUT_COMMAND="gtimeout"
else
    echo "timeout (or gtimeout on macOS) is required to bound Docker operations during the Trivy container scan" >&2
    exit 69
fi
readonly DOCKER_TIMEOUT_COMMAND

if [[ "${#SERVICES[@]}" -eq 0 ]]; then
    echo "No service Dockerfiles found in infrastructure/docker" >&2
    exit 66
fi

for service in "${SERVICES[@]}"; do
    if ! validate_image_reference "${IMAGE_PREFIX}/${service}:${IMAGE_TAG}"; then
        exit 64
    fi
done

run_docker_operation() {
    "${DOCKER_TIMEOUT_COMMAND}" --signal=TERM --kill-after=10s "${DOCKER_OPERATION_TIMEOUT_SECONDS}s" docker "$@"
}

docker_mount_source() {
    local source="$1"

    # Docker parses --mount parameters as CSV. Quote the complete source= field so a cache
    # directory containing a comma remains one field; CSV represents a literal quote as "".
    source="${source//\"/\"\"}"
    printf '"source=%s"' "${source}"
}

if run_docker_operation info >/dev/null 2>&1; then
    :
else
    docker_status=$?
    if [[ "${docker_status}" -eq "${DOCKER_TIMEOUT_EXIT_STATUS}" ]]; then
        echo "Docker daemon check timed out after ${DOCKER_OPERATION_TIMEOUT_SECONDS}s" >&2
        exit 69
    fi
    echo "Docker daemon is unavailable or inaccessible; start Docker and verify access before scanning images" >&2
    exit 69
fi

for service in "${SERVICES[@]}"; do
    image="${IMAGE_PREFIX}/${service}:${IMAGE_TAG}"
    if run_docker_operation image inspect "${image}" >/dev/null 2>&1; then
        continue
    else
        docker_status=$?
    fi
    if [[ "${docker_status}" -eq "${DOCKER_TIMEOUT_EXIT_STATUS}" ]]; then
        echo "Container image availability check for ${image} timed out after ${DOCKER_OPERATION_TIMEOUT_SECONDS}s" >&2
        exit 69
    fi
    echo "Container image ${image} is missing; run scripts/build-container-images.sh first" >&2
    exit 66
done

for trivy_cache_command in mkdir rmdir sleep; do
    if ! command -v "${trivy_cache_command}" >/dev/null 2>&1; then
        echo "${trivy_cache_command} is required to coordinate access to the Trivy cache" >&2
        exit 69
    fi
done

if ! mkdir -p "${TRIVY_CACHE_DIR}"; then
    echo "Unable to create the Trivy cache directory ${TRIVY_CACHE_DIR}" >&2
    exit 69
fi

trivy_cache_lock_is_held() {
    [[ -d "${TRIVY_CACHE_LOCK_DIRECTORY}" && ! -L "${TRIVY_CACHE_LOCK_DIRECTORY}" ]]
}

acquire_trivy_cache_lock() {
    local deadline_seconds=$((SECONDS + TRIVY_CACHE_LOCK_TIMEOUT_SECONDS))
    local mkdir_error

    while true; do
        if mkdir_error="$(mkdir "${TRIVY_CACHE_LOCK_DIRECTORY}" 2>&1)"; then
            return 0
        fi

        # An actual lock directory is the only expected mkdir failure. A holder can release it
        # between the failed mkdir above and this check, so retry once before reporting a
        # malformed cache path or permission error. Do not treat a symlink as lock contention.
        if ! trivy_cache_lock_is_held; then
            if mkdir_error="$(mkdir "${TRIVY_CACHE_LOCK_DIRECTORY}" 2>&1)"; then
                return 0
            fi
            if ! trivy_cache_lock_is_held; then
                printf 'Unable to acquire exclusive access to the Trivy cache: %s\n' \
                    "${mkdir_error:-mkdir failed without a diagnostic}" >&2
                return 1
            fi
        fi

        if (( SECONDS >= deadline_seconds )); then
            echo "Timed out waiting for exclusive access to the Trivy cache" >&2
            return 1
        fi
        sleep "${TRIVY_CACHE_LOCK_POLL_SECONDS}"
    done
}

release_trivy_cache_lock() {
    rmdir "${TRIVY_CACHE_LOCK_DIRECTORY}" 2>/dev/null || true
}

if ! acquire_trivy_cache_lock; then
    exit 69
fi
trap release_trivy_cache_lock EXIT

for service in "${SERVICES[@]}"; do
    image="${IMAGE_PREFIX}/${service}:${IMAGE_TAG}"
    if run_docker_operation run --rm \
        --mount "type=bind,$(docker_mount_source "${TRIVY_CACHE_DIR}"),target=/root/.cache" \
        --volume /var/run/docker.sock:/var/run/docker.sock \
        "${TRIVY_IMAGE}" \
        image \
        --no-progress \
        --exit-code 1 \
        --ignore-unfixed \
        --severity HIGH,CRITICAL \
        "${image}"; then
        continue
    else
        docker_status=$?
    fi
    if [[ "${docker_status}" -eq "${DOCKER_TIMEOUT_EXIT_STATUS}" ]]; then
        echo "Trivy image scan for ${image} timed out after ${DOCKER_OPERATION_TIMEOUT_SECONDS}s" >&2
        exit 69
    fi
    exit "${docker_status}"
done
