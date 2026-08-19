#!/usr/bin/env bash
set -euo pipefail

readonly REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# Pin the scanner by digest rather than a mutable tag. Update it intentionally with its scan policy
# and report format, then verify the new image in CI before changing this trusted toolchain input.
readonly TRIVY_IMAGE="${LIFEOS_TRIVY_IMAGE:-aquasec/trivy:0.67.0@sha256:94711c60051c6cab848a292e3a67f62623fcee361b2bb661f43b17184f4afdac}"
# Keep the scanner database outside /repo. Otherwise a repeat local scan can recursively inspect
# its own multi-gigabyte vulnerability cache and turn a source-security gate into an I/O bottleneck.
readonly TRIVY_CACHE_DIR="${LIFEOS_TRIVY_CACHE_DIR:-${RUNNER_TEMP:-/tmp}/lifeos-trivy-cache}"

if ! command -v docker >/dev/null 2>&1; then
    echo "docker is required to run the Trivy source security scan" >&2
    exit 69
fi

mkdir -p "${TRIVY_CACHE_DIR}"

docker run --rm \
    --volume "${TRIVY_CACHE_DIR}:/root/.cache" \
    --volume "${REPOSITORY_ROOT}:/repo:ro" \
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
    .
