#!/usr/bin/env bash
set -euo pipefail

if ! command -v dirname >/dev/null 2>&1; then
    echo "dirname is required to resolve the repository root" >&2
    exit 69
fi

REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly REPOSITORY_ROOT
# Pin the trusted scanner by digest rather than accepting an environment replacement. Update it
# intentionally with its scan policy and report format, then verify the new image in CI.
readonly TRIVY_IMAGE="aquasec/trivy:0.67.0@sha256:94711c60051c6cab848a292e3a67f62623fcee361b2bb661f43b17184f4afdac"
# Keep the scanner database outside /repo. Otherwise a repeat local scan can recursively inspect
# its own multi-gigabyte vulnerability cache and turn a source-security gate into an I/O bottleneck.
readonly TRIVY_CACHE_DIR_INPUT="${LIFEOS_TRIVY_CACHE_DIR:-${RUNNER_TEMP:-/tmp}/lifeos-trivy-cache}"
readonly TRIVY_CACHE_LOCK_TIMEOUT_SECONDS=300
readonly TRIVY_CACHE_LOCK_POLL_SECONDS=1
# Docker can hang while connecting to its daemon, pulling the scanner image, or streaming a
# scan. Keep every invocation within a single, deliberately bounded operator-configurable
# deadline so a broken Docker dependency cannot consume the enclosing CI-job timeout.
readonly DOCKER_OPERATION_TIMEOUT_SECONDS="${LIFEOS_DOCKER_TIMEOUT_SECONDS:-300}"
readonly DOCKER_TIMEOUT_EXIT_STATUS=124

if [[ ! "${DOCKER_OPERATION_TIMEOUT_SECONDS}" =~ ^[1-9][0-9]{0,2}$ ]] \
    || (( 10#${DOCKER_OPERATION_TIMEOUT_SECONDS} > 900 )); then
    echo "LIFEOS_DOCKER_TIMEOUT_SECONDS must be between 1 and 900 seconds" >&2
    exit 64
fi

# A relative source can be interpreted as a Docker-managed named volume instead of the directory
# protected by this process's cache lock. Keep the lock and the scanner on one explicit host path.
if [[ "${TRIVY_CACHE_DIR_INPUT}" != /* ]]; then
    echo "LIFEOS_TRIVY_CACHE_DIR must be an absolute path" >&2
    exit 64
fi

if ! command -v mkdir >/dev/null 2>&1; then
    echo "mkdir is required to prepare the Trivy cache directory" >&2
    exit 69
fi
if ! mkdir -p "${TRIVY_CACHE_DIR_INPUT}"; then
    echo "Unable to create the Trivy cache directory ${TRIVY_CACHE_DIR_INPUT}" >&2
    exit 69
fi

if ! CANONICAL_REPOSITORY_ROOT="$(cd -- "${REPOSITORY_ROOT}" && pwd -P)"; then
    echo "Unable to canonicalize the repository root for Trivy cache validation" >&2
    exit 69
fi
if ! CANONICAL_TRIVY_CACHE_DIR="$(cd -- "${TRIVY_CACHE_DIR_INPUT}" && pwd -P)"; then
    echo "Unable to canonicalize LIFEOS_TRIVY_CACHE_DIR" >&2
    exit 69
fi
readonly CANONICAL_REPOSITORY_ROOT CANONICAL_TRIVY_CACHE_DIR
case "${CANONICAL_TRIVY_CACHE_DIR}" in
    "${CANONICAL_REPOSITORY_ROOT}" | "${CANONICAL_REPOSITORY_ROOT}"/*)
        echo "LIFEOS_TRIVY_CACHE_DIR must not be inside the repository checkout" >&2
        exit 64
        ;;
esac
readonly TRIVY_CACHE_DIR="${CANONICAL_TRIVY_CACHE_DIR}"
# Trivy's filesystem cache uses an exclusive BoltDB lock. Coordinate every LifeOS scan that uses
# this cache so image and source scans retain warm-cache behavior without racing the database.
readonly TRIVY_CACHE_LOCK_DIRECTORY="${TRIVY_CACHE_DIR}/.lifeos-trivy-cache.lock"

if ! command -v docker >/dev/null 2>&1; then
    echo "docker is required to run the Trivy source security scan" >&2
    exit 69
fi

if command -v timeout >/dev/null 2>&1; then
    DOCKER_TIMEOUT_COMMAND="timeout"
elif command -v gtimeout >/dev/null 2>&1; then
    # macOS ships no timeout utility; Homebrew's coreutils exposes the GNU-compatible command
    # as gtimeout. Prefer timeout on CI/Linux while retaining a clear local development path.
    DOCKER_TIMEOUT_COMMAND="gtimeout"
else
    echo "timeout (or gtimeout on macOS) is required to bound Docker operations during the Trivy source security scan" >&2
    exit 69
fi
readonly DOCKER_TIMEOUT_COMMAND

run_docker_operation() {
    "${DOCKER_TIMEOUT_COMMAND}" --signal=TERM --kill-after=10s "${DOCKER_OPERATION_TIMEOUT_SECONDS}s" docker "$@"
}

docker_mount_source() {
    local source="$1"

    # Docker parses --mount parameters as CSV. Quote the complete source= field so a repository
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
    echo "Docker daemon is unavailable or inaccessible; start Docker and verify access before running the Trivy source security scan" >&2
    exit 69
fi

for trivy_cache_command in rmdir sleep; do
    if ! command -v "${trivy_cache_command}" >/dev/null 2>&1; then
        echo "${trivy_cache_command} is required to coordinate access to the Trivy cache" >&2
        exit 69
    fi
done

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

if run_docker_operation run --rm \
    --mount "type=bind,$(docker_mount_source "${TRIVY_CACHE_DIR}"),target=/root/.cache" \
    --mount "type=bind,$(docker_mount_source "${REPOSITORY_ROOT}"),target=/repo,readonly" \
    --workdir /repo \
    "${TRIVY_IMAGE}" \
    fs \
    --no-progress \
    --exit-code 1 \
    --ignore-unfixed \
    --scanners vuln,secret,misconfig \
    --severity HIGH,CRITICAL \
    --skip-dirs .git \
    --skip-dirs .gradle \
    .; then
    :
else
    docker_status=$?
    if [[ "${docker_status}" -eq "${DOCKER_TIMEOUT_EXIT_STATUS}" ]]; then
        echo "Trivy source security scan timed out after ${DOCKER_OPERATION_TIMEOUT_SECONDS}s" >&2
        exit 69
    fi
    exit "${docker_status}"
fi
