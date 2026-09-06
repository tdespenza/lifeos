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
readonly DOCKER_TIMEOUT_SIGNAL_EXIT_STATUS=137
readonly IMAGE_REFERENCE_VALIDATION_SCRIPT="${REPOSITORY_ROOT}/scripts/image-reference-validation.sh"
if [[ ! -f "${IMAGE_REFERENCE_VALIDATION_SCRIPT}" || ! -r "${IMAGE_REFERENCE_VALIDATION_SCRIPT}" ]]; then
    echo "Image reference validation library is required" >&2
    exit 69
fi
# shellcheck disable=SC1090,SC1091
source "${IMAGE_REFERENCE_VALIDATION_SCRIPT}"
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

is_docker_timeout_status() {
    local docker_status="$1"

    [[ "${docker_status}" -eq "${DOCKER_TIMEOUT_EXIT_STATUS}" \
        || "${docker_status}" -eq "${DOCKER_TIMEOUT_SIGNAL_EXIT_STATUS}" ]]
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
    if is_docker_timeout_status "${docker_status}"; then
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
    if is_docker_timeout_status "${docker_status}"; then
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
            TRIVY_CACHE_LOCK_HELD=true
            return 0
        fi

        # An actual lock directory is the only expected mkdir failure. A holder can release it
        # between the failed mkdir above and this check, so retry once before reporting a
        # malformed cache path or permission error. Do not treat a symlink as lock contention.
        if ! trivy_cache_lock_is_held; then
            if mkdir_error="$(mkdir "${TRIVY_CACHE_LOCK_DIRECTORY}" 2>&1)"; then
                TRIVY_CACHE_LOCK_HELD=true
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
    if [[ "${TRIVY_CACHE_LOCK_HELD:-false}" != true ]]; then
        return 0
    fi
    rmdir "${TRIVY_CACHE_LOCK_DIRECTORY}" 2>/dev/null || true
    TRIVY_CACHE_LOCK_HELD=false
}

TRIVY_CACHE_LOCK_HELD=false
trap release_trivy_cache_lock EXIT

for service in "${SERVICES[@]}"; do
    image="${IMAGE_PREFIX}/${service}:${IMAGE_TAG}"
    if ! acquire_trivy_cache_lock; then
        exit 69
    fi

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
        docker_status=0
    else
        docker_status=$?
    fi

    release_trivy_cache_lock
    TRIVY_CACHE_LOCK_HELD=false

    if [[ "${docker_status}" -eq 0 ]]; then
        continue
    fi
    if is_docker_timeout_status "${docker_status}"; then
        echo "Trivy image scan for ${image} timed out after ${DOCKER_OPERATION_TIMEOUT_SECONDS}s" >&2
        exit 69
    fi
    exit "${docker_status}"
done
