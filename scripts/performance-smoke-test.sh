#!/usr/bin/env bash
set -euo pipefail

REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
readonly REPOSITORY_ROOT
readonly TARGET_URL="${LIFEOS_PERFORMANCE_GATEWAY_MANAGEMENT_BASE_URL:-}"
readonly VUS="${LIFEOS_PERFORMANCE_VUS:-10}"
readonly DURATION="${LIFEOS_PERFORMANCE_DURATION:-15s}"
readonly K6_SCRIPT="${REPOSITORY_ROOT}/scripts/performance/readiness-smoke.js"
readonly K6_IMAGE="grafana/k6@sha256:b24f418fc99a26dd57904c952c03bfaf79462be18508acc45aafa07ff68e7df2"
# This bounds the user-controlled input processed by canonicalize_path; it is not an OS PATH_MAX.
readonly PERFORMANCE_SUMMARY_PATH_MAX_LENGTH=4096

temporary_summary_path=""

cleanup_temporary_summary() {
    # Keep a failed Docker run from leaving its private bind-mount source in the host temp directory.
    if [[ -n "${temporary_summary_path}" ]]; then
        rm -f -- "${temporary_summary_path}"
    fi
}

trap cleanup_temporary_summary EXIT

install_summary_securely() {
    local source_path="$1"
    local destination_path="$2"
    local python_runner

    if ! python_runner="$(command -p -v python3 2>/dev/null)" || [[ ! -x "${python_runner}" ]]; then
        for python_runner in /usr/bin/python3 /usr/local/bin/python3 /opt/homebrew/bin/python3; do
            if [[ -x "${python_runner}" ]]; then
                break
            fi
        done
        if [[ ! -x "${python_runner}" ]]; then
            echo "python3 is required to install the performance summary safely" >&2
            return 1
        fi
    fi

    # Open every destination directory from a directory descriptor with O_NOFOLLOW, creating
    # missing components relative to that descriptor. The final rename also uses the open parent
    # descriptor, so a concurrent replacement of any path component cannot redirect the report
    # outside the validated repository tree.
    "${python_runner}" - "${REPOSITORY_ROOT}" "${source_path}" "${destination_path}" <<'PY'
import os
import stat
import sys

repository_root, source_path, destination_path = sys.argv[1:]
relative_destination = os.path.relpath(destination_path, repository_root)
if relative_destination == os.curdir or relative_destination.startswith(os.pardir + os.sep):
    raise SystemExit("performance summary destination escaped the repository root")

components = relative_destination.split(os.sep)
if not components or any(component in ("", os.curdir, os.pardir) for component in components):
    raise SystemExit("performance summary destination contains invalid components")

no_follow_flag = getattr(os, "O_NOFOLLOW", 0)
if not no_follow_flag:
    raise SystemExit("the host does not support no-follow summary path traversal")
directory_flags = os.O_RDONLY | getattr(os, "O_DIRECTORY", 0) | no_follow_flag
directory_fd = os.open(repository_root, directory_flags)
staging_name = None
try:
    for component in components[:-1]:
        try:
            os.mkdir(component, 0o755, dir_fd=directory_fd)
        except FileExistsError:
            pass
        next_directory_fd = os.open(component, directory_flags, dir_fd=directory_fd)
        if not stat.S_ISDIR(os.fstat(next_directory_fd).st_mode):
            os.close(next_directory_fd)
            raise SystemExit("performance summary path component is not a directory")
        os.close(directory_fd)
        directory_fd = next_directory_fd

    # Stage the private source in the destination directory, then rename(2) it over the final
    # name. This keeps the atomic replacement safe even when TMPDIR and the repository are on
    # different filesystems; rename replaces a final symlink rather than following it.
    source_fd = os.open(source_path, os.O_RDONLY | no_follow_flag)
    try:
        for attempt in range(100):
            candidate = ".lifeos-k6-summary.{}.{}".format(os.getpid(), attempt)
            try:
                staging_fd = os.open(
                    candidate,
                    os.O_WRONLY | os.O_CREAT | os.O_EXCL | no_follow_flag,
                    0o644,
                    dir_fd=directory_fd,
                )
                staging_name = candidate
                break
            except FileExistsError:
                continue
        if staging_name is None:
            raise SystemExit("unable to allocate a private summary staging file")

        with os.fdopen(source_fd, "rb", closefd=True) as source, os.fdopen(staging_fd, "wb", closefd=True) as staging:
            while True:
                chunk = source.read(1024 * 1024)
                if not chunk:
                    break
                staging.write(chunk)
            staging.flush()
            os.fsync(staging.fileno())
        source_fd = None
    finally:
        if source_fd is not None:
            os.close(source_fd)

    os.replace(staging_name, components[-1], src_dir_fd=directory_fd, dst_dir_fd=directory_fd)
    staging_name = None
    os.unlink(source_path)
finally:
    if staging_name is not None:
        try:
            os.unlink(staging_name, dir_fd=directory_fd)
        except FileNotFoundError:
            pass
    os.close(directory_fd)
PY
}

canonicalize_path() {
    # Resolve lexical path components and symlinks without creating output directories first.
    # Input is capped at 4,096 characters before splitting. For N components, the queue shifts and
    # prefix rebuilds make the worst case O(N^2) time and O(N) space; the cap keeps that bounded.
    # Symlink expansion is separately capped at 40 hops. An unbounded caller should instead use an
    # indexed component scan with an incremental stack to make canonicalization linear.
    local input_path="$1"
    local candidate_path component link_target resolved_component
    local symlink_hops=0
    local -a pending_components=()
    local -a resolved_components=()
    local -a link_components=()

    if (( ${#input_path} > PERFORMANCE_SUMMARY_PATH_MAX_LENGTH )); then
        return 1
    fi

    if [[ "${input_path}" == *$'\n'* ]]; then
        return 1
    fi

    if [[ "${input_path}" == /* ]]; then
        candidate_path="${input_path}"
    else
        candidate_path="${REPOSITORY_ROOT}/${input_path}"
    fi
    IFS='/' read -r -a pending_components <<< "${candidate_path#/}"

    while [[ "${#pending_components[@]}" -gt 0 ]]; do
        component="${pending_components[0]}"
        pending_components=("${pending_components[@]:1}")
        case "${component}" in
            ''|.)
                continue
                ;;
            ..)
                if [[ "${#resolved_components[@]}" -gt 0 ]]; then
                    resolved_components=("${resolved_components[@]:0:${#resolved_components[@]} - 1}")
                fi
                continue
                ;;
        esac

        candidate_path="/"
        if [[ "${#resolved_components[@]}" -gt 0 ]]; then
            for resolved_component in "${resolved_components[@]}"; do
                candidate_path="${candidate_path%/}/${resolved_component}"
            done
        fi
        candidate_path="${candidate_path%/}/${component}"

        if [[ -L "${candidate_path}" ]]; then
            ((symlink_hops += 1))
            if (( symlink_hops > 40 )); then
                return 1
            fi
            link_target="$(readlink "${candidate_path}")" || return 1
            IFS='/' read -r -a link_components <<< "${link_target#/}"
            if [[ "${link_target}" == /* ]]; then
                resolved_components=()
            fi
            pending_components=("${link_components[@]-}" "${pending_components[@]-}")
        else
            resolved_components+=("${component}")
        fi
    done

    candidate_path="/"
    if [[ "${#resolved_components[@]}" -gt 0 ]]; then
        for resolved_component in "${resolved_components[@]}"; do
            candidate_path="${candidate_path%/}/${resolved_component}"
        done
    fi
    printf '%s\n' "${candidate_path}"
}

readonly SUMMARY_PATH_INPUT="${LIFEOS_PERFORMANCE_SUMMARY_PATH:-${REPOSITORY_ROOT}/build/reports/performance/k6-summary.json}"
if ! SUMMARY_PATH="$(canonicalize_path "${SUMMARY_PATH_INPUT}")"; then
    if (( ${#SUMMARY_PATH_INPUT} > PERFORMANCE_SUMMARY_PATH_MAX_LENGTH )); then
        echo "LIFEOS_PERFORMANCE_SUMMARY_PATH must not exceed ${PERFORMANCE_SUMMARY_PATH_MAX_LENGTH} characters" >&2
    else
        echo "LIFEOS_PERFORMANCE_SUMMARY_PATH must resolve to a valid path" >&2
    fi
    exit 64
fi
readonly SUMMARY_PATH

if [[ ! "${TARGET_URL}" =~ ^https://[^/@?#]+(/[^?#]*)?$ ]]; then
    echo "LIFEOS_PERFORMANCE_GATEWAY_MANAGEMENT_BASE_URL must be a canonical HTTPS URL" >&2
    exit 64
fi

if [[ ! "${VUS}" =~ ^([1-9]|[1-9][0-9]|100)$ ]] || (( VUS > 100 )); then
    echo "LIFEOS_PERFORMANCE_VUS must be between 1 and 100" >&2
    exit 64
fi

if [[ ! "${DURATION}" =~ ^([5-9]|[1-5][0-9]|60)s$ ]]; then
    echo "LIFEOS_PERFORMANCE_DURATION must be between 5s and 60s" >&2
    exit 64
fi

if [[ "${SUMMARY_PATH}" != "${REPOSITORY_ROOT}/"* ]]; then
    echo "LIFEOS_PERFORMANCE_SUMMARY_PATH must stay under the repository root" >&2
    exit 64
fi
if ! command -v mktemp >/dev/null 2>&1; then
    echo "mktemp is required to stage the performance summary safely" >&2
    exit 69
fi

if command -v k6 >/dev/null 2>&1; then
    temporary_summary_path="$(mktemp "${TMPDIR:-/tmp}/lifeos-k6-summary.XXXXXX")" || {
        echo "Unable to create a temporary k6 summary file" >&2
        exit 73
    }
    k6 run \
        --quiet \
        --summary-export "${temporary_summary_path}" \
        --env "TARGET_URL=${TARGET_URL}" \
        --env "VUS=${VUS}" \
        --env "DURATION=${DURATION}" \
        "${K6_SCRIPT}"
    if [[ ! -s "${temporary_summary_path}" ]]; then
        echo "k6 did not produce a performance summary: ${SUMMARY_PATH}" >&2
        exit 65
    fi
    if ! install_summary_securely "${temporary_summary_path}" "${SUMMARY_PATH}"; then
        echo "Unable to install the performance summary safely: ${SUMMARY_PATH}" >&2
        exit 73
    fi
    temporary_summary_path=""
elif command -v docker >/dev/null 2>&1; then
    temporary_summary_path="$(mktemp "${TMPDIR:-/tmp}/lifeos-k6-summary.XXXXXX")" || {
        echo "Unable to create a temporary k6 summary file" >&2
        exit 73
    }
    container_summary_path="/tmp/$(basename "${temporary_summary_path}")"

    docker run --rm \
        --user "$(id -u):$(id -g)" \
        --volume "${REPOSITORY_ROOT}:/work:ro" \
        --volume "${temporary_summary_path}:${container_summary_path}" \
        --workdir /work \
        "${K6_IMAGE}" \
        run \
        --quiet \
        --summary-export "${container_summary_path}" \
        --env "TARGET_URL=${TARGET_URL}" \
        --env "VUS=${VUS}" \
        --env "DURATION=${DURATION}" \
        "/work/scripts/performance/readiness-smoke.js"

    if [[ ! -s "${temporary_summary_path}" ]]; then
        echo "k6 did not produce a performance summary: ${SUMMARY_PATH}" >&2
        exit 65
    fi
    if ! install_summary_securely "${temporary_summary_path}" "${SUMMARY_PATH}"; then
        echo "Unable to install the performance summary safely: ${SUMMARY_PATH}" >&2
        exit 73
    fi
    temporary_summary_path=""
else
    echo "k6 or Docker is required to run the performance smoke test" >&2
    exit 69
fi

if [[ ! -s "${SUMMARY_PATH}" ]]; then
    echo "k6 did not produce a performance summary: ${SUMMARY_PATH}" >&2
    exit 65
fi

printf '%s\n' "Performance smoke report written to ${SUMMARY_PATH}"
