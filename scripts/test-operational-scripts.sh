#!/usr/bin/env bash
set -euo pipefail

# This file also acts as the deterministic command double used by the tests below. Each harness
# places symlinks named docker, timeout, jq, curl, k6, rg, and sleep in PATH, so the operational scripts
# are exercised exactly as they are invoked in CI without requiring Docker, a network connection,
# or a k6 binary.
fake_log_command() {
    local command_name="$1"
    shift

    : "${FAKE_COMMAND_LOG:?FAKE_COMMAND_LOG must be set for command doubles}"
    local line="${command_name}"
    local argument
    for argument in "$@"; do
        line+=$'\t'"${argument}"
    done
    printf '%s\n' "${line}" >> "${FAKE_COMMAND_LOG}"
}

is_health_probe_log_entry() {
    local line="$1"
    local request_url="${line##*$'\t'}"

    [[ "${line}" == $'curl\t'* && "${request_url}" == https://* \
        && "${request_url}" == *'/actuator/health'* ]]
}

fake_docker() {
    fake_log_command docker "$@"

    if [[ "${1:-}" == "run" && -n "${FAKE_DOCKER_RUN_STARTED_FILE:-}" ]]; then
        if [[ -z "${FAKE_DOCKER_RUN_RELEASE_FILE:-}" ]]; then
            printf '%s\n' 'FAKE_DOCKER_RUN_RELEASE_FILE is required when blocking a fake Docker run' >&2
            return 64
        fi
        : > "${FAKE_DOCKER_RUN_STARTED_FILE}"
        while [[ ! -e "${FAKE_DOCKER_RUN_RELEASE_FILE}" ]]; do
            command -p sleep 0.05
        done
    fi

    if [[ "${1:-}" == "info" ]]; then
        return "${FAKE_DOCKER_INFO_STATUS:-0}"
    fi

    if [[ "${1:-}" == "image" && "${2:-}" == "inspect" ]]; then
        return "${FAKE_DOCKER_IMAGE_INSPECT_STATUS:-0}"
    fi

    if [[ "${1:-}" == "compose" && "${2:-}" == "version" ]]; then
        if [[ "${FAKE_DOCKER_COMPOSE_VERSION_STATUS:-0}" == "0" ]]; then
            printf '%s\n' "${FAKE_DOCKER_COMPOSE_VERSION_OUTPUT:-2.17.0}"
        fi
        return "${FAKE_DOCKER_COMPOSE_VERSION_STATUS:-0}"
    fi

    if [[ "${1:-}" == "compose" && " $* " == *" up "* ]]; then
        if [[ -n "${FAKE_DOCKER_COMPOSE_UP_MESSAGE:-}" ]]; then
            printf '%s\n' "${FAKE_DOCKER_COMPOSE_UP_MESSAGE}" >&2
        fi
        return "${FAKE_DOCKER_COMPOSE_UP_STATUS:-0}"
    fi

    if [[ "${1:-}" == "compose" && " $* " == *" exec "* \
        && -n "${FAKE_DOCKER_STDIN_LOG:-}" ]]; then
        cat > "${FAKE_DOCKER_STDIN_LOG}"
    fi

    if [[ "${1:-}" == "run" && " $* " == *" --summary-export "* ]]; then
        local argument summary_volume=""
        for argument in "$@"; do
            if [[ "${argument}" == *:/tmp/lifeos-k6-summary.* ]]; then
                summary_volume="${argument%:/tmp/lifeos-k6-summary.*}"
                break
            fi
        done
        if [[ -z "${summary_volume}" ]]; then
            printf '%s\n' 'fake Docker requires a file mount for the k6 summary' >&2
            return 64
        fi
        printf '%s\n' '{"metrics":{}}' > "${summary_volume}"
    fi

    return "${FAKE_DOCKER_STATUS:-0}"
}

fake_timeout() {
    local argument
    local docker_argument_index=0
    local docker_subcommand=""
    local argument_index
    local next_argument_index
    local docker_command_start_index
    for ((argument_index = 1; argument_index <= $#; argument_index += 1)); do
        argument="${!argument_index}"
        if [[ "${argument}" == "docker" ]]; then
            docker_argument_index="${argument_index}"
            if (( argument_index < $# )); then
                next_argument_index=$((argument_index + 1))
                docker_subcommand="${!next_argument_index}"
            fi
            break
        fi
    done

    if (( docker_argument_index == 0 || docker_argument_index == $# )); then
        printf '%s\n' 'fake timeout requires a docker command' >&2
        return 64
    fi

    # Record the wrapper's nested command as one field. Deliberately omit its remaining Docker
    # arguments: the delegated fake Docker call records those, and duplicating them would make
    # command-count assertions mistake the wrapper for a second Trivy invocation.
    fake_log_command "${0##*/}" \
        "${@:1:$((docker_argument_index - 1))}" \
        "docker ${docker_subcommand}"

    if [[ -n "${FAKE_TIMEOUT_STATUS+x}" \
        && ( -z "${FAKE_TIMEOUT_DOCKER_SUBCOMMAND:-}" \
            || "${docker_subcommand}" == "${FAKE_TIMEOUT_DOCKER_SUBCOMMAND}" ) ]]; then
        return "${FAKE_TIMEOUT_STATUS}"
    fi

    docker_command_start_index=$((docker_argument_index + 1))
    docker "${@:docker_command_start_index}"
}

fake_jq() {
    fake_log_command jq "$@"

    if [[ "$*" == *'type == "object" and all(.[]; type == "string")'* ]]; then
        # This exact staging predicate has input-dependent jq semantics, so use the system jq
        # outside the harness PATH to make malformed and structurally invalid fixtures fail alike.
        if [[ -z "${SYSTEM_JQ:-}" || ! -x "${SYSTEM_JQ}" ]]; then
            printf '%s\n' 'System jq is required to validate staging smoke JSON fixtures' >&2
            return 69
        fi

        local validation_input
        validation_input="$(cat)"
        if ! "${SYSTEM_JQ}" --exit-status 'type == "object" and all(.[]; type == "string")' \
            <<< "${validation_input}" >/dev/null 2>&1; then
            return 64
        fi
        return 0
    fi

    if [[ " $* " == *" --raw-output "* && -n "${FAKE_JQ_SERVICE_URL:-}" ]]; then
        printf '%s\n' "${FAKE_JQ_SERVICE_URL}"
        return 0
    fi

    if [[ "$*" == *'.status == "UP"'* ]]; then
        # Health-status sequences exercise the real jq predicate against fake curl's JSON response.
        # Existing tests can still force a generic predicate failure without constructing a response.
        if [[ "${FAKE_JQ_READINESS_STATUS:-0}" != "0" ]]; then
            cat >/dev/null
            return "${FAKE_JQ_READINESS_STATUS}"
        fi
        if [[ -n "${FAKE_CURL_HEALTH_STATUS_SEQUENCE:-}" \
            || -n "${FAKE_CURL_CHUNKED_HEALTH_VALID_PREFIX_RESPONSE_BYTES:-}" ]]; then
            if [[ -z "${SYSTEM_JQ:-}" || ! -x "${SYSTEM_JQ}" ]]; then
                printf '%s\n' 'System jq is required to validate fake health responses' >&2
                return 69
            fi
            "${SYSTEM_JQ}" "$@"
            return
        fi

        cat >/dev/null
        return 0
    fi

    if [[ " $* " == *" --raw-input "* ]]; then
        local value
        while IFS= read -r value; do
            printf '"%s"\n' "${value}"
        done
        return 0
    fi

    if [[ " $* " == *" --slurp "* ]]; then
        while IFS= read -r _; do
            :
        done
        printf '%s\n' '["mock-service"]'
        return 0
    fi

    if [[ " $* " == *" -cn "* ]]; then
        printf '%s\n' '{"mock":true}'
        return 0
    fi

    printf '%s\n' '{}'
}

fake_curl() {
    fake_log_command curl "$@"

    local argument dump_header_file="" output_file="" redirect_url="" url=""
    local follows_redirect=false
    local maximum_redirects=""
    local writes_status_code=false
    local argument_index
    for ((argument_index = 1; argument_index <= $#; argument_index += 1)); do
        argument="${!argument_index}"
        case "${argument}" in
            --dump-header)
                ((argument_index += 1))
                dump_header_file="${!argument_index}"
                ;;
            --output)
                ((argument_index += 1))
                output_file="${!argument_index}"
                ;;
            --location)
                follows_redirect=true
                ;;
            --max-redirs)
                ((argument_index += 1))
                maximum_redirects="${!argument_index}"
                ;;
            --write-out)
                ((argument_index += 1))
                writes_status_code=true
                ;;
            https://*)
                url="${argument}"
                ;;
        esac
    done

    redirect_url="${FAKE_CURL_REDIRECT_URL:-}"
    if [[ -n "${redirect_url}" && "${url}" == "${redirect_url}" ]]; then
        if [[ -n "${dump_header_file}" ]]; then
            printf 'HTTP/1.1 %s Redirect\r\n' "${FAKE_CURL_REDIRECT_STATUS:-302}" > "${dump_header_file}"
            printf 'X-Correlation-ID: %s\r\n\r\n' \
                "${FAKE_CURL_REDIRECT_INTERMEDIATE_CORRELATION_ID:-redirect-correlation-id}" \
                >> "${dump_header_file}"
            if [[ "${follows_redirect}" == "true" && "${maximum_redirects}" != "0" ]]; then
                printf 'HTTP/1.1 200 OK\r\nX-Correlation-ID: %s\r\n\r\n' \
                    "${FAKE_CURL_REDIRECT_FINAL_CORRELATION_ID:-final-correlation-id}" \
                    >> "${dump_header_file}"
            fi
        fi

        if [[ "${follows_redirect}" == "true" && "${maximum_redirects}" == "0" ]]; then
            return 47
        fi
        if [[ "${follows_redirect}" == "true" ]]; then
            if [[ " $* " == *" --write-out "* ]]; then
                printf '200'
            fi
            return 0
        fi
    fi

    if [[ -n "${FAKE_CURL_ACCOUNT_REGISTRATION_STATUS_CODE:-}" && "${url}" == */api/v1/accounts ]]; then
        local correlation_header_separator=' '
        case "${FAKE_CURL_ACCOUNT_REGISTRATION_CORRELATION_SEPARATOR:-space}" in
            space)
                ;;
            htab)
                correlation_header_separator=$'\t'
                ;;
            none)
                correlation_header_separator=''
                ;;
            *)
                printf 'Unsupported fake correlation-header separator: %s\n' \
                    "${FAKE_CURL_ACCOUNT_REGISTRATION_CORRELATION_SEPARATOR}" >&2
                return 64
                ;;
        esac
        if [[ -n "${dump_header_file}" ]]; then
            printf 'HTTP/1.1 %s Response\r\nX-Correlation-ID:%s%s\r\n\r\n' \
                "${FAKE_CURL_ACCOUNT_REGISTRATION_STATUS_CODE}" \
                "${correlation_header_separator}" \
                "${FAKE_CURL_ACCOUNT_REGISTRATION_CORRELATION_ID:-11111111-1111-4111-8111-111111111111}" \
                > "${dump_header_file}"
        fi
        if [[ -n "${output_file}" ]]; then
            printf '%s\n' '{"error":"mocked registration validation failure"}' > "${output_file}"
        fi
        if [[ "${writes_status_code}" == "true" ]]; then
            printf '%s' "${FAKE_CURL_ACCOUNT_REGISTRATION_STATUS_CODE}"
        fi
        return "${FAKE_CURL_STATUS:-0}"
    fi

    if [[ -n "${FAKE_CURL_ACCOUNT_BLOCK_STARTED_FILE:-}" && "${url}" == */api/v1/accounts ]]; then
        if [[ -z "${FAKE_CURL_ACCOUNT_BLOCK_RELEASE_FILE:-}" ]]; then
            printf '%s\n' 'FAKE_CURL_ACCOUNT_BLOCK_RELEASE_FILE is required when blocking a fake account request' >&2
            return 64
        fi

        : > "${FAKE_CURL_ACCOUNT_BLOCK_STARTED_FILE}"
        while [[ ! -e "${FAKE_CURL_ACCOUNT_BLOCK_RELEASE_FILE}" ]]; do
            command -p sleep 0.05
        done
    fi

    if [[ -n "${FAKE_CURL_HEALTH_BLOCK_STARTED_FILE:-}" && "${url}" == *'/actuator/health'* ]]; then
        if [[ -z "${FAKE_CURL_HEALTH_BLOCK_RELEASE_FILE:-}" ]]; then
            printf '%s\n' 'FAKE_CURL_HEALTH_BLOCK_RELEASE_FILE is required when blocking a fake health request' >&2
            return 64
        fi

        : > "${FAKE_CURL_HEALTH_BLOCK_STARTED_FILE}"
        while [[ ! -e "${FAKE_CURL_HEALTH_BLOCK_RELEASE_FILE}" ]]; do
            command -p sleep 0.05
        done
    fi

    if [[ -n "${FAKE_CURL_HEALTH_STATUS_SEQUENCE:-}" && "${url}" == *'/actuator/health'* ]]; then
        local health_status_index=0
        local health_request_count=0
        local health_status
        local log_line
        local -a health_statuses

        IFS=',' read -r -a health_statuses <<< "${FAKE_CURL_HEALTH_STATUS_SEQUENCE}"
        if [[ "${#health_statuses[@]}" -eq 0 || -z "${health_statuses[0]}" ]]; then
            printf '%s\n' 'FAKE_CURL_HEALTH_STATUS_SEQUENCE must contain at least one status' >&2
            return 64
        fi

        while IFS= read -r log_line; do
            if is_health_probe_log_entry "${log_line}"; then
                ((health_request_count += 1))
            fi
        done < "${FAKE_COMMAND_LOG}"
        health_status_index=$((health_request_count - 1))
        if (( health_status_index >= ${#health_statuses[@]} )); then
            health_status_index=$((${#health_statuses[@]} - 1))
        fi
        health_status="${health_statuses[health_status_index]}"
        printf '{"status":"%s"}\n' "${health_status}"
        return "${FAKE_CURL_STATUS:-0}"
    fi

    if [[ -n "${FAKE_CURL_CHUNKED_HEALTH_VALID_PREFIX_RESPONSE_BYTES:-}" \
        && "${url}" == *'/actuator/health'* ]]; then
        # Emit only a body, with no Content-Length metadata, to model an oversized chunked
        # response. The first cap bytes are valid JSON plus whitespace; the final byte is garbage.
        local response_bytes="${FAKE_CURL_CHUNKED_HEALTH_VALID_PREFIX_RESPONSE_BYTES}"
        local response_prefix='{"status":"UP"}'
        local response_overhead=$(( ${#response_prefix} + 1 ))
        local padding_length

        if [[ ! "${response_bytes}" =~ ^[1-9][0-9]{0,6}$ ]] \
            || (( 10#${response_bytes} <= response_overhead || 10#${response_bytes} > 1048576 )); then
            printf '%s\n' 'FAKE_CURL_CHUNKED_HEALTH_VALID_PREFIX_RESPONSE_BYTES must be between the JSON overhead and 1048576' >&2
            return 64
        fi

        padding_length=$((10#${response_bytes} - response_overhead))
        printf '%s%*sX' "${response_prefix}" "${padding_length}" ''
        return "${FAKE_CURL_STATUS:-0}"
    fi

    if [[ -n "${FAKE_CURL_STDOUT:-}" ]]; then
        printf '%s\n' "${FAKE_CURL_STDOUT}"
    fi
    return "${FAKE_CURL_STATUS:-0}"
}

fake_rg() {
    fake_log_command rg "$@"

    if [[ -n "${FAKE_RG_STATUS+x}" ]]; then
        cat >/dev/null
        return "${FAKE_RG_STATUS}"
    fi

    local argument pattern=""
    local ignore_case=false
    for argument in "$@"; do
        case "${argument}" in
            --ignore-case | -i)
                ignore_case=true
                ;;
            --quiet | -q | --)
                ;;
            -*)
                ;;
            *)
                pattern="${argument}"
                ;;
        esac
    done

    if [[ -z "${pattern}" ]]; then
        printf '%s\n' 'fake rg requires a pattern' >&2
        return 64
    fi

    # Bash's regex engine expects a literal tab rather than ripgrep's \t escape sequence.
    pattern="${pattern//\\t/$'\t'}"
    if [[ "${ignore_case}" == "true" ]]; then
        shopt -s nocasematch
    fi

    local line
    while IFS= read -r line || [[ -n "${line}" ]]; do
        if [[ "${line}" =~ ${pattern} ]]; then
            return 0
        fi
    done
    return 1
}

fake_sleep() {
    fake_log_command sleep "$@"
    if [[ -n "${FAKE_SLEEP_REAL_DELAY:-}" ]]; then
        command -p sleep "${FAKE_SLEEP_REAL_DELAY}"
    fi
    return "${FAKE_SLEEP_STATUS:-0}"
}

fake_mkdir() {
    fake_log_command mkdir "$@"

    local argument
    for argument in "$@"; do
        if [[ -n "${FAKE_MKDIR_FAILURE_PATH:-}" && "${argument}" == "${FAKE_MKDIR_FAILURE_PATH}" ]]; then
            if [[ -n "${FAKE_MKDIR_FAILURE_ONCE_FILE:-}" ]]; then
                if [[ -e "${FAKE_MKDIR_FAILURE_ONCE_FILE}" ]]; then
                    command -p mkdir "$@"
                    return
                fi
                : > "${FAKE_MKDIR_FAILURE_ONCE_FILE}"
            fi
            printf 'fake mkdir forced failure for %s\n' "${argument}" >&2
            return "${FAKE_MKDIR_FAILURE_STATUS:-1}"
        fi
    done

    command -p mkdir "$@"
}

fake_rmdir() {
    fake_log_command rmdir "$@"
    command -p rmdir "$@"
}

fake_mktemp() {
    fake_log_command mktemp "$@"

    if [[ -n "${FAKE_MKTEMP_FAIL_ON_CALL:-}" ]]; then
        if [[ -z "${FAKE_MKTEMP_CALL_COUNT_FILE:-}" ]]; then
            printf '%s\n' 'FAKE_MKTEMP_CALL_COUNT_FILE is required when forcing a fake mktemp failure' >&2
            return 64
        fi

        local call_count=0
        if [[ -e "${FAKE_MKTEMP_CALL_COUNT_FILE}" ]]; then
            if ! IFS= read -r call_count < "${FAKE_MKTEMP_CALL_COUNT_FILE}" \
                || [[ ! "${call_count}" =~ ^[0-9]+$ ]]; then
                printf '%s\n' 'FAKE_MKTEMP_CALL_COUNT_FILE must contain a non-negative integer' >&2
                return 64
            fi
        fi

        ((call_count += 1))
        printf '%s\n' "${call_count}" > "${FAKE_MKTEMP_CALL_COUNT_FILE}"
        if [[ "${call_count}" == "${FAKE_MKTEMP_FAIL_ON_CALL}" ]]; then
            printf 'fake mktemp forced failure on call %s\n' "${call_count}" >&2
            return "${FAKE_MKTEMP_FAILURE_STATUS:-1}"
        fi
    fi

    command -p mktemp "$@"
}

fake_dirname() {
    fake_log_command dirname "$@"
    printf '%s\n' "${FAKE_DIRNAME_OUTPUT:-/definitely-missing-lifeos-script-directory}"
}

fake_find() {
    fake_log_command find "$@"

    if [[ -n "${FAKE_FIND_PARTIAL_OUTPUT:-}" ]]; then
        printf '%s\n' "${FAKE_FIND_PARTIAL_OUTPUT}"
    fi
    return "${FAKE_FIND_STATUS:-0}"
}

fake_k6() {
    fake_log_command k6 "$@"

    local summary_path=""
    local index
    for ((index = 1; index <= $#; index += 1)); do
        if [[ "${!index}" == "--summary-export" ]]; then
            local next_index=$((index + 1))
            summary_path="${!next_index}"
            break
        fi
    done

    if [[ -z "${summary_path}" ]]; then
        printf '%s\n' 'fake k6 requires --summary-export' >&2
        return 64
    fi

    mkdir -p "$(dirname "${summary_path}")"
    printf '%s\n' '{"metrics":{}}' > "${summary_path}"
}

case "${0##*/}" in
    docker)
        fake_docker "$@"
        exit
        ;;
    timeout)
        fake_timeout "$@"
        exit
        ;;
    gtimeout)
        fake_timeout "$@"
        exit
        ;;
    jq)
        fake_jq "$@"
        exit
        ;;
    curl)
        fake_curl "$@"
        exit
        ;;
    k6)
        fake_k6 "$@"
        exit
        ;;
    rg)
        fake_rg "$@"
        exit
        ;;
    sleep)
        fake_sleep "$@"
        exit
        ;;
    mkdir)
        fake_mkdir "$@"
        exit
        ;;
    rmdir)
        fake_rmdir "$@"
        exit
        ;;
    mktemp)
        fake_mktemp "$@"
        exit
        ;;
    dirname)
        fake_dirname "$@"
        exit
        ;;
    find)
        fake_find "$@"
        exit
        ;;
    test-operational-scripts.sh)
        ;;
    *)
        printf 'Unsupported operational-test command double: %s\n' "${0##*/}" >&2
        exit 64
        ;;
esac

SCRIPT_DIRECTORY="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_DIRECTORY
REPOSITORY_ROOT="$(cd -- "${SCRIPT_DIRECTORY}/.." && pwd)"
readonly REPOSITORY_ROOT
TEST_SCRIPT_PATH="${SCRIPT_DIRECTORY}/${BASH_SOURCE[0]##*/}"
readonly TEST_SCRIPT_PATH
TEST_DIRECTORIES=()
RUN_OUTPUT=""
RUN_STATUS=0

cleanup() {
    local directory
    for directory in "${TEST_DIRECTORIES[@]}"; do
        if [[ -d "${directory}" ]]; then
            rm -rf -- "${directory}"
        fi
    done
}
trap cleanup EXIT

fail() {
    printf 'FAIL: %s\n' "$*" >&2
    exit 1
}

if ! SYSTEM_JQ="$(command -v jq)"; then
    fail "System jq is required by the operational test harness"
fi
readonly SYSTEM_JQ
export SYSTEM_JQ

assert_status() {
    local expected_status="$1"
    local description="$2"
    if [[ "${RUN_STATUS}" -ne "${expected_status}" ]]; then
        fail "${description}: expected exit ${expected_status}, got ${RUN_STATUS}"
    fi
}

assert_nonzero_status() {
    local description="$1"
    if [[ "${RUN_STATUS}" -eq 0 ]]; then
        fail "${description}: command unexpectedly succeeded"
    fi
}

assert_file_contains() {
    local file="$1"
    local expected="$2"
    local description="$3"
    local contents=""
    assert_readable_file "${file}" "${description}"
    IFS= read -r -d '' contents < "${file}" || true
    if [[ "${contents}" != *"${expected}"* ]]; then
        fail "${description}: missing '${expected}'"
    fi
}

assert_file_excludes() {
    local file="$1"
    local unexpected="$2"
    local description="$3"
    local contents=""
    assert_readable_file "${file}" "${description}"
    IFS= read -r -d '' contents < "${file}" || true
    if [[ "${contents}" == *"${unexpected}"* ]]; then
        fail "${description}: found unexpected '${unexpected}'"
    fi
}

assert_readable_file() {
    local file="$1"
    local description="$2"
    if [[ ! -f "${file}" ]]; then
        fail "${description}: required file is missing: ${file}"
    fi
    if [[ ! -r "${file}" ]]; then
        fail "${description}: required file is not readable: ${file}"
    fi
}

assert_log_contains() {
    local root="$1"
    local expected="$2"
    local description="$3"
    assert_file_contains "${root}/commands.log" "${expected}" "${description}"
}

assert_log_excludes() {
    local root="$1"
    local unexpected="$2"
    local description="$3"
    assert_file_excludes "${root}/commands.log" "${unexpected}" "${description}"
}

assert_no_logged_command() {
    local root="$1"
    local command="$2"
    local description="$3"
    local line

    assert_readable_file "${root}/commands.log" "${description}"
    while IFS= read -r line; do
        if [[ "${line}" == "${command}" || "${line}" == "${command}"$'\t'* ]]; then
            fail "${description}: found ${command} command"
        fi
    done < "${root}/commands.log"
}

assert_no_logged_docker_subcommand() {
    local root="$1"
    local subcommand="$2"
    local description="$3"
    local line

    assert_readable_file "${root}/commands.log" "${description}"
    while IFS= read -r line; do
        if [[ "${line}" == $'docker\t'"${subcommand}"$'\t'* ]]; then
            fail "${description}: found docker ${subcommand} command"
        fi
    done < "${root}/commands.log"
}

assert_log_entry_excludes() {
    local root="$1"
    local entry_marker="$2"
    local unexpected="$3"
    local description="$4"
    local log_file="${root}/commands.log"

    if [[ -z "${entry_marker}" || "${entry_marker}" == *$'\n'* ]]; then
        fail "${description}: assert_log_entry_excludes requires a non-empty single-line entry marker"
    fi

    assert_readable_file "${log_file}" "${description} command"
    local matching_entries
    matching_entries="$(grep -F -- "${entry_marker}" "${log_file}" || true)"
    if [[ -z "${matching_entries}" ]]; then
        fail "${description} command: missing '${entry_marker}'"
    fi
    if grep -Fq -- "${unexpected}" <<< "${matching_entries}"; then
        fail "${description}: found unexpected '${unexpected}'"
    fi
}

assert_log_order() {
    local root="$1"
    local first="$2"
    local second="$3"
    local description="$4"
    local first_line second_line

    if [[ "${first}" == *$'\n'* || "${second}" == *$'\n'* ]]; then
        fail "${description}: assert_log_order requires single-line patterns"
    fi
    assert_log_contains "${root}" "${first}" "${description} first command"
    assert_log_contains "${root}" "${second}" "${description} second command"
    first_line="$(grep -Fnm 1 -- "${first}" "${root}/commands.log" | cut -d: -f1)"
    second_line="$(grep -Fnm 1 -- "${second}" "${root}/commands.log" | cut -d: -f1)"
    if (( first_line >= second_line )); then
        fail "${description}: command order is incorrect"
    fi
}

assert_log_line_count() {
    local root="$1"
    local expected="$2"
    local expected_count="$3"
    local description="$4"
    local actual_count

    if [[ "${expected}" == *$'\n'* ]]; then
        fail "${description}: assert_log_line_count requires a single-line pattern"
    fi
    assert_readable_file "${root}/commands.log" "${description}"
    actual_count="$(grep -F -c -- "${expected}" "${root}/commands.log" || true)"
    if [[ "${actual_count}" -ne "${expected_count}" ]]; then
        fail "${description}: expected ${expected_count} matching commands, got ${actual_count}"
    fi
}

assert_curl_retry_probe_count() {
    local root="$1"
    local expected_count="$2"
    local description="$3"
    local line
    local actual_count=0

    assert_readable_file "${root}/commands.log" "${description}"
    while IFS= read -r line; do
        if [[ "${line}" == $'curl\t'* && "${line}" == *$'\t--retry\t'* ]]; then
            ((actual_count += 1))
        fi
    done < "${root}/commands.log"

    if [[ "${actual_count}" -ne "${expected_count}" ]]; then
        fail "${description}: expected ${expected_count} curl retry probes, got ${actual_count}"
    fi
}

assert_health_probe_count() {
    local root="$1"
    local expected_count="$2"
    local description="$3"
    local line
    local actual_count=0

    assert_readable_file "${root}/commands.log" "${description}"
    while IFS= read -r line; do
        if is_health_probe_log_entry "${line}"; then
            ((actual_count += 1))
        fi
    done < "${root}/commands.log"

    if [[ "${actual_count}" -ne "${expected_count}" ]]; then
        fail "${description}: expected ${expected_count} health probes, got ${actual_count}"
    fi
}

assert_no_commands_logged() {
    local root="$1"
    local message="$2"

    if [[ -s "${root}/commands.log" ]]; then
        fail "${message}"
    fi
}

assert_directory_empty() {
    local directory="$1"
    local description="$2"
    local entry

    if [[ ! -d "${directory}" ]]; then
        fail "${description}: required directory is missing: ${directory}"
    fi
    entry="$(command -p find "${directory}" -mindepth 1 -print -quit)"
    if [[ -n "${entry}" ]]; then
        fail "${description}: found unexpected temporary file: ${entry}"
    fi
}

new_harness() {
    local name="$1"
    shift

    TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/lifeos-operational-${name}.XXXXXX")"
    TEST_ROOT="$(cd -P -- "${TEST_ROOT}" && pwd -P)"
    TEST_DIRECTORIES+=("${TEST_ROOT}")
    mkdir -p \
        "${TEST_ROOT}/bin" \
        "${TEST_ROOT}/infrastructure/docker" \
        "${TEST_ROOT}/infrastructure/docker-compose" \
        "${TEST_ROOT}/scripts"
    cp "${REPOSITORY_ROOT}/scripts/https-authority-validation.sh" \
        "${TEST_ROOT}/scripts/https-authority-validation.sh"

    local script signal_reset_runner
    for script in "$@"; do
        mkdir -p "${TEST_ROOT}/scripts/$(dirname "${script}")"
        cp "${REPOSITORY_ROOT}/scripts/${script}" "${TEST_ROOT}/scripts/${script}"
    done

    local command
    for command in docker timeout gtimeout jq curl k6 rg sleep; do
        ln -s "${TEST_SCRIPT_PATH}" "${TEST_ROOT}/bin/${command}"
    done
    : > "${TEST_ROOT}/commands.log"
}

add_service_dockerfile() {
    local root="$1"
    local service="$2"
    touch "${root}/infrastructure/docker/${service}.Dockerfile"
}

add_service_jar() {
    local root="$1"
    local service="$2"
    local jar_name="$3"
    mkdir -p "${root}/services/${service}/build/libs"
    touch "${root}/services/${service}/build/libs/${jar_name}"
}

add_database_provisioning_sql() {
    local root="$1"
    cp "${REPOSITORY_ROOT}/infrastructure/docker-compose/provision-databases.sql" \
        "${root}/infrastructure/docker-compose/provision-databases.sql"
}

disable_fake_command() {
    local root="$1"
    local command="$2"
    unlink "${root}/bin/${command}"
}

add_prerequisite_command() {
    local root="$1"
    local command="$2"
    local command_path

    command_path="$(command -p -v "${command}")" || fail "System ${command} is required by the operational test harness"
    mkdir -p "${root}/prerequisite-bin"
    ln -s "${command_path}" "${root}/prerequisite-bin/${command}"
}

add_service_discovery_prerequisites_except() {
    local root="$1"
    local missing_command="$2"
    local command
    shift 2

    for command in bash dirname "$@" find basename sort; do
        if [[ "${command}" != "${missing_command}" ]]; then
            add_prerequisite_command "${root}" "${command}"
        fi
    done
}

add_failing_find_double() {
    local root="$1"

    ln -s "${TEST_SCRIPT_PATH}" "${root}/bin/find"
}

add_mkdir_double() {
    local root="$1"

    ln -s "${TEST_SCRIPT_PATH}" "${root}/bin/mkdir"
}

add_rmdir_double() {
    local root="$1"

    ln -s "${TEST_SCRIPT_PATH}" "${root}/bin/rmdir"
}

add_mktemp_double() {
    local root="$1"

    ln -s "${TEST_SCRIPT_PATH}" "${root}/bin/mktemp"
}

execute_target() {
    local root="$1"
    local script="$2"
    shift 2

    export PATH="${root}/bin:${PATH}"
    export FAKE_COMMAND_LOG="${root}/commands.log"
    # Normalise the shell diagnostic asserted by root-resolution regression tests.
    export LC_ALL=C
    unset \
        BASH_ENV \
        GITHUB_RUN_ID \
        GITHUB_REF_NAME \
        GITHUB_REPOSITORY \
        GITHUB_SHA \
        LIFEOS_CHAOS_EXPERIMENT_WEBHOOK_URL \
        LIFEOS_CHAOS_GATEWAY_HEALTH_URL \
        LIFEOS_CHAOS_IDENTITY_HEALTH_URL \
        LIFEOS_CHAOS_TASK_GOAL_HEALTH_URL \
        LIFEOS_DATABASE_PROVISION_TIMEOUT_SECONDS \
        LIFEOS_DOCKER_TIMEOUT_SECONDS \
        LIFEOS_E2E_GATEWAY_BASE_URL \
        LIFEOS_E2E_GATEWAY_MANAGEMENT_BASE_URL \
        LIFEOS_E2E_IDENTITY_MANAGEMENT_BASE_URL \
        LIFEOS_IMAGE_PREFIX \
        LIFEOS_IMAGE_TAG \
        LIFEOS_PERFORMANCE_DURATION \
        LIFEOS_PERFORMANCE_GATEWAY_MANAGEMENT_BASE_URL \
        LIFEOS_PERFORMANCE_SUMMARY_PATH \
        LIFEOS_PERFORMANCE_VUS \
        LIFEOS_PROVISION_CONCURRENCY_POSTGRES_IMAGE \
        LIFEOS_OPERATIONAL_TEST_NO_NATIVE_K6 \
        LIFEOS_PUSH_IMAGES \
        LIFEOS_TRIVY_CACHE_DIR \
        LIFEOS_TRIVY_IMAGE \
        FAKE_DOCKER_COMPOSE_VERSION_STATUS \
        FAKE_DOCKER_COMPOSE_VERSION_OUTPUT \
        FAKE_DOCKER_COMPOSE_UP_MESSAGE \
        FAKE_DOCKER_COMPOSE_UP_STATUS \
        FAKE_DOCKER_INFO_STATUS \
        FAKE_DOCKER_IMAGE_INSPECT_STATUS \
        FAKE_DOCKER_RUN_RELEASE_FILE \
        FAKE_DOCKER_RUN_STARTED_FILE \
        FAKE_DOCKER_STDIN_LOG \
        FAKE_DOCKER_STATUS \
        FAKE_TIMEOUT_DOCKER_SUBCOMMAND \
        FAKE_TIMEOUT_STATUS \
        FAKE_CURL_STATUS \
        FAKE_CURL_STDOUT \
        FAKE_CURL_REDIRECT_FINAL_CORRELATION_ID \
        FAKE_CURL_REDIRECT_INTERMEDIATE_CORRELATION_ID \
        FAKE_CURL_REDIRECT_STATUS \
        FAKE_CURL_REDIRECT_URL \
        FAKE_CURL_ACCOUNT_REGISTRATION_CORRELATION_ID \
        FAKE_CURL_ACCOUNT_REGISTRATION_CORRELATION_SEPARATOR \
        FAKE_CURL_ACCOUNT_REGISTRATION_STATUS_CODE \
        FAKE_CURL_ACCOUNT_BLOCK_RELEASE_FILE \
        FAKE_CURL_ACCOUNT_BLOCK_STARTED_FILE \
        FAKE_CURL_HEALTH_BLOCK_RELEASE_FILE \
        FAKE_CURL_HEALTH_BLOCK_STARTED_FILE \
        FAKE_CURL_CHUNKED_HEALTH_VALID_PREFIX_RESPONSE_BYTES \
        FAKE_CURL_HEALTH_STATUS_SEQUENCE \
        FAKE_DIRNAME_OUTPUT \
        FAKE_FIND_PARTIAL_OUTPUT \
        FAKE_FIND_STATUS \
        FAKE_JQ_READINESS_STATUS \
        FAKE_JQ_SERVICE_URL \
        FAKE_MKDIR_FAILURE_PATH \
        FAKE_MKDIR_FAILURE_ONCE_FILE \
        FAKE_MKDIR_FAILURE_STATUS \
        FAKE_MKTEMP_CALL_COUNT_FILE \
        FAKE_MKTEMP_FAIL_ON_CALL \
        FAKE_MKTEMP_FAILURE_STATUS \
        FAKE_RG_STATUS \
        FAKE_RESET_SIGNAL_DISPOSITIONS \
        FAKE_SLEEP_REAL_DELAY \
        FAKE_SLEEP_STATUS \
        FAKE_BASH_RANDOM_SEED \
        RUNNER_TEMP \
        STAGING_SERVICE_HEALTH_URLS_JSON \
        STAGING_DEPLOY_WEBHOOK_URL
    while [[ $# -gt 0 && "$1" =~ ^[A-Za-z_][A-Za-z0-9_]*= ]]; do
        declare -x "$1"
        shift
    done
    if [[ "${LIFEOS_OPERATIONAL_TEST_NO_NATIVE_K6:-false}" == "true" ]]; then
        # Keep native-k6 absence deterministic even when a developer's host PATH has k6.
        # Each fallback test supplies only its explicit non-k6 prerequisites in this directory.
        export PATH="${root}/bin:${root}/prerequisite-bin"
    fi
    # Both callers run this helper in a subshell. Replacing that subshell makes the background
    # PID used by interruption regressions the actual script process, so termination exercises
    # the production EXIT cleanup rather than only killing a harness wrapper.
    if [[ "${FAKE_RESET_SIGNAL_DISPOSITIONS:-false}" == "true" ]]; then
        if ! signal_reset_runner="$(command -p -v perl)"; then
            printf '%s\n' 'Perl is required to run signal-handling regressions' >&2
            exit 69
        fi
        # Background Bash jobs inherit ignored INT/HUP dispositions. Reset the three signals in
        # this test-only exec trampoline so the target script's own traps receive each signal.
        # shellcheck disable=SC2016 # Perl must receive its signal and argument variables literally.
        exec "${signal_reset_runner}" -e \
            '$SIG{HUP} = "DEFAULT"; $SIG{INT} = "DEFAULT"; $SIG{TERM} = "DEFAULT"; exec @ARGV or die "exec failed: $!\\n";' \
            bash "${root}/scripts/${script}" "$@"
    fi
    if [[ -n "${FAKE_BASH_RANDOM_SEED:-}" ]]; then
        exec bash -c 'RANDOM="$1"; source "$2"' bash \
            "${FAKE_BASH_RANDOM_SEED}" "${root}/scripts/${script}"
    fi
    exec bash "${root}/scripts/${script}" "$@"
}

run_target() {
    local root="$1"
    local script="$2"
    shift 2

    RUN_OUTPUT="${root}/output.log"
    : > "${root}/commands.log"
    if (execute_target "${root}" "${script}" "$@") > "${RUN_OUTPUT}" 2>&1; then
        RUN_STATUS=0
    else
        RUN_STATUS=$?
    fi
}

start_target() {
    local root="$1"
    local script="$2"
    local output="$3"
    shift 3

    (execute_target "${root}" "${script}" "$@") > "${output}" 2>&1 &
    BACKGROUND_TARGET_PID=$!
}

wait_for_log_line_count() {
    local root="$1"
    local expected="$2"
    local expected_count="$3"
    local attempts="${4:-100}"
    local actual_count
    local attempt

    for ((attempt = 0; attempt < attempts; attempt++)); do
        actual_count="$(grep -F -c -- "${expected}" "${root}/commands.log" || true)"
        if [[ "${actual_count}" -eq "${expected_count}" ]]; then
            return 0
        fi
        command -p sleep 0.05
    done
    return 1
}

wait_for_file() {
    local file="$1"
    local attempts="${2:-100}"
    local attempt

    for ((attempt = 0; attempt < attempts; attempt++)); do
        if [[ -e "${file}" ]]; then
            return 0
        fi
        command -p sleep 0.05
    done
    return 1
}

test_run_target_exports_only_valid_environment_assignments() {
    new_harness run-target-arguments
    local target_script="${TEST_ROOT}/scripts/argument-probe.sh"

    printf '%s\n' \
        '#!/usr/bin/env bash' \
        'set -euo pipefail' \
        "printf 'environment=%s\\n' \"\${RUN_TARGET_TEST_ENV:-missing}\"" \
        "printf 'argument=%s\\n' \"\${1-<missing>}\"" \
        > "${target_script}"

    run_target "${TEST_ROOT}" argument-probe.sh \
        "RUN_TARGET_TEST_ENV=preserved" \
        "--payload=literal"

    assert_status 0 "run_target with an argument containing an equals sign"
    assert_file_contains "${RUN_OUTPUT}" "environment=preserved" \
        "run_target valid environment assignment"
    assert_file_contains "${RUN_OUTPUT}" "argument=--payload=literal" \
        "run_target non-assignment argument"
}

test_file_assertions_match_full_file_literals() {
    new_harness file-assertions
    local assertion_file="${TEST_ROOT}/assertions.txt"
    printf '%s\n' \
        'begin' \
        'literal [value]*?' \
        'end' > "${assertion_file}"

    assert_file_contains "${assertion_file}" \
        $'begin\nliteral [value]*?\nend' \
        "full-file literal assertion"
    assert_file_excludes "${assertion_file}" \
        $'begin\nmissing\nend' \
        "non-contiguous multiline assertion"
    if (assert_file_contains "${assertion_file}" $'begin\nmissing\nend' \
        "non-contiguous multiline assertion") >/dev/null 2>&1; then
        fail "full-file multiline assertion must reject non-contiguous content"
    fi
}

test_file_assertions_fail_clearly_when_files_are_missing() {
    new_harness file-assertions-missing
    local missing_file="${TEST_ROOT}/missing.txt"

    RUN_OUTPUT="${TEST_ROOT}/contains-missing-output.log"
    if (assert_file_contains "${missing_file}" "expected" "missing contains assertion") \
        > "${RUN_OUTPUT}" 2>&1; then
        fail "file-contains assertion must fail when its file is missing"
    fi
    assert_file_contains "${RUN_OUTPUT}" "required file is missing: ${missing_file}" \
        "file-contains missing-file diagnostic"
    assert_file_excludes "${RUN_OUTPUT}" "unbound variable" \
        "file-contains missing-file diagnostic"

    RUN_OUTPUT="${TEST_ROOT}/excludes-missing-output.log"
    if (assert_file_excludes "${missing_file}" "unexpected" "missing excludes assertion") \
        > "${RUN_OUTPUT}" 2>&1; then
        fail "file-excludes assertion must fail when its file is missing"
    fi
    assert_file_contains "${RUN_OUTPUT}" "required file is missing: ${missing_file}" \
        "file-excludes missing-file diagnostic"
    assert_file_excludes "${RUN_OUTPUT}" "unbound variable" \
        "file-excludes missing-file diagnostic"
}

test_log_assertions_fail_clearly_for_invalid_patterns_and_missing_logs() {
    new_harness log-assertions
    printf '%s\n' 'first command' 'second command' > "${TEST_ROOT}/commands.log"

    RUN_OUTPUT="${TEST_ROOT}/multiline-order-output.log"
    if (assert_log_order "${TEST_ROOT}" $'first command\nsecond command' "second command" \
        "multiline log order") > "${RUN_OUTPUT}" 2>&1; then
        fail "log-order assertion must reject multiline patterns"
    fi
    assert_file_contains "${RUN_OUTPUT}" \
        "multiline log order: assert_log_order requires single-line patterns" \
        "log-order multiline-pattern diagnostic"

    RUN_OUTPUT="${TEST_ROOT}/multiline-count-output.log"
    if (assert_log_line_count "${TEST_ROOT}" $'first command\nsecond command' 1 \
        "multiline log count") > "${RUN_OUTPUT}" 2>&1; then
        fail "log-line-count assertion must reject multiline patterns"
    fi
    assert_file_contains "${RUN_OUTPUT}" \
        "multiline log count: assert_log_line_count requires a single-line pattern" \
        "log-line-count multiline-pattern diagnostic"

    RUN_OUTPUT="${TEST_ROOT}/empty-entry-marker-output.log"
    if (assert_log_entry_excludes "${TEST_ROOT}" "" "--retry" "empty entry marker") \
        > "${RUN_OUTPUT}" 2>&1; then
        fail "log-entry assertion must reject an empty marker"
    fi
    assert_file_contains "${RUN_OUTPUT}" \
        "empty entry marker: assert_log_entry_excludes requires a non-empty single-line entry marker" \
        "log-entry empty-marker diagnostic"

    RUN_OUTPUT="${TEST_ROOT}/multiline-entry-marker-output.log"
    if (assert_log_entry_excludes "${TEST_ROOT}" $'first command\nsecond command' "--retry" \
        "multiline entry marker") > "${RUN_OUTPUT}" 2>&1; then
        fail "log-entry assertion must reject a multiline marker"
    fi
    assert_file_contains "${RUN_OUTPUT}" \
        "multiline entry marker: assert_log_entry_excludes requires a non-empty single-line entry marker" \
        "log-entry multiline-marker diagnostic"

    RUN_OUTPUT="${TEST_ROOT}/missing-entry-marker-output.log"
    if (assert_log_entry_excludes "${TEST_ROOT}" "missing command" "--retry" \
        "missing entry marker") > "${RUN_OUTPUT}" 2>&1; then
        fail "log-entry assertion must reject a missing marker"
    fi
    assert_file_contains "${RUN_OUTPUT}" \
        "missing entry marker command: missing 'missing command'" \
        "log-entry missing-marker diagnostic"

    rm -f -- "${TEST_ROOT}/commands.log"
    RUN_OUTPUT="${TEST_ROOT}/missing-retry-probe-log-output.log"
    if (assert_curl_retry_probe_count "${TEST_ROOT}" 0 "missing retry-probe log") \
        > "${RUN_OUTPUT}" 2>&1; then
        fail "curl retry-probe assertion must reject a missing command log"
    fi
    assert_file_contains "${RUN_OUTPUT}" \
        "missing retry-probe log: required file is missing: ${TEST_ROOT}/commands.log" \
        "curl retry-probe missing-log diagnostic"

    printf '%s\n' 'docker run' > "${TEST_ROOT}/commands.log"
    RUN_OUTPUT="${TEST_ROOT}/unexpected-command-output.log"
    if (assert_no_commands_logged "${TEST_ROOT}" "unexpected-command diagnostic") \
        > "${RUN_OUTPUT}" 2>&1; then
        fail "no-commands assertion must reject a non-empty command log"
    fi
    assert_file_contains "${RUN_OUTPUT}" "unexpected-command diagnostic" \
        "no-commands assertion diagnostic"
}

test_command_double_dispatch_fails_closed() {
    new_harness command-double-dispatch
    ln -s "${TEST_SCRIPT_PATH}" "${TEST_ROOT}/bin/unrecognized-command"

    RUN_OUTPUT="${TEST_ROOT}/unknown-command-output.log"
    if "${TEST_ROOT}/bin/unrecognized-command" > "${RUN_OUTPUT}" 2>&1; then
        RUN_STATUS=0
    else
        RUN_STATUS=$?
    fi

    assert_status 64 "unrecognized command double"
    assert_file_contains "${RUN_OUTPUT}" \
        "Unsupported operational-test command double: unrecognized-command" \
        "unrecognized command double"
}

test_build_rejects_missing_services() {
    new_harness build-no-services build-container-images.sh

    run_target "${TEST_ROOT}" build-container-images.sh

    assert_status 66 "container build without Dockerfiles"
    assert_file_contains "${RUN_OUTPUT}" "No service Dockerfiles found" "container build without Dockerfiles"
    assert_no_commands_logged "${TEST_ROOT}" \
        "container build without Dockerfiles must not invoke Docker"
}

test_build_rejects_missing_or_ambiguous_jars() {
    new_harness build-missing-jar build-container-images.sh
    add_service_dockerfile "${TEST_ROOT}" example-service

    run_target "${TEST_ROOT}" build-container-images.sh

    assert_status 66 "container build without a service jar"
    assert_file_contains "${RUN_OUTPUT}" "Expected exactly one non-plain executable jar" "container build without a service jar"
    assert_no_commands_logged "${TEST_ROOT}" \
        "container build without a service jar must not invoke Docker"

    new_harness build-ambiguous-jar build-container-images.sh
    add_service_dockerfile "${TEST_ROOT}" example-service
    add_service_jar "${TEST_ROOT}" example-service example-service-a.jar
    add_service_jar "${TEST_ROOT}" example-service example-service-b.jar
    add_service_jar "${TEST_ROOT}" example-service example-service-plain.jar

    run_target "${TEST_ROOT}" build-container-images.sh

    assert_status 66 "container build with ambiguous jars"
    assert_file_contains "${RUN_OUTPUT}" "Expected exactly one non-plain executable jar" "container build with ambiguous jars"
    assert_no_commands_logged "${TEST_ROOT}" \
        "container build with ambiguous jars must not invoke Docker"
}

test_build_passes_jar_argument_and_honors_push_switch() {
    new_harness build-with-push build-container-images.sh
    add_service_dockerfile "${TEST_ROOT}" example-service
    add_service_jar "${TEST_ROOT}" example-service example-service.jar
    add_service_jar "${TEST_ROOT}" example-service example-service-plain.jar

    run_target "${TEST_ROOT}" build-container-images.sh \
        "LIFEOS_IMAGE_PREFIX=registry.example/lifeos" \
        "LIFEOS_IMAGE_TAG=build-42" \
        "LIFEOS_PUSH_IMAGES=true"

    assert_status 0 "container build with one executable jar"
    assert_log_contains "${TEST_ROOT}" \
        $'docker\tbuild\t--build-arg\tJAR_FILE=services/example-service/build/libs/example-service.jar' \
        "container build jar argument"
    assert_log_contains "${TEST_ROOT}" \
        $'--file\t'"${TEST_ROOT}"$'/infrastructure/docker/example-service.Dockerfile' \
        "container build Dockerfile argument"
    assert_log_contains "${TEST_ROOT}" \
        $'--tag\tregistry.example/lifeos/example-service:build-42' \
        "container build image tag"
    assert_log_contains "${TEST_ROOT}" \
        $'docker\tpush\tregistry.example/lifeos/example-service:build-42' \
        "container image push"

    new_harness build-without-push build-container-images.sh
    add_service_dockerfile "${TEST_ROOT}" example-service
    add_service_jar "${TEST_ROOT}" example-service example-service.jar

    run_target "${TEST_ROOT}" build-container-images.sh LIFEOS_PUSH_IMAGES=false

    assert_status 0 "container build with pushes disabled"
    assert_log_excludes "${TEST_ROOT}" $'docker\tpush\t' "disabled container image push"
}

test_container_build_validates_and_bounds_docker_operations() {
    local invalid_timeout

    for invalid_timeout in 0 901 nonnumeric; do
        new_harness "build-invalid-docker-timeout-${invalid_timeout}" build-container-images.sh
        add_service_dockerfile "${TEST_ROOT}" example-service
        add_service_jar "${TEST_ROOT}" example-service example-service.jar

        run_target "${TEST_ROOT}" build-container-images.sh \
            "LIFEOS_DOCKER_TIMEOUT_SECONDS=${invalid_timeout}"

        assert_status 64 "container build with invalid Docker timeout ${invalid_timeout}"
        assert_file_contains "${RUN_OUTPUT}" \
            "LIFEOS_DOCKER_TIMEOUT_SECONDS must be between 1 and 900 seconds" \
            "container build invalid Docker-timeout diagnostic"
        assert_no_commands_logged "${TEST_ROOT}" \
            "container build with invalid Docker timeout must not invoke Docker"
    done

    new_harness build-operation-timeout build-container-images.sh
    add_service_dockerfile "${TEST_ROOT}" example-service
    add_service_jar "${TEST_ROOT}" example-service example-service.jar

    run_target "${TEST_ROOT}" build-container-images.sh \
        "LIFEOS_DOCKER_TIMEOUT_SECONDS=1" \
        "FAKE_TIMEOUT_DOCKER_SUBCOMMAND=build" \
        "FAKE_TIMEOUT_STATUS=124"

    assert_status 69 "container build with a timed out Docker build"
    assert_file_contains "${RUN_OUTPUT}" \
        "Container image build for lifeos/example-service:local timed out after 1s" \
        "container build timeout diagnostic"
    assert_log_contains "${TEST_ROOT}" \
        $'timeout\t--signal=TERM\t--kill-after=10s\t1s\tdocker build' \
        "container build timeout wrapper"
    assert_no_logged_docker_subcommand "${TEST_ROOT}" build \
        "container build must not invoke Docker after its timeout wrapper expires"

    new_harness build-push-timeout build-container-images.sh
    add_service_dockerfile "${TEST_ROOT}" example-service
    add_service_jar "${TEST_ROOT}" example-service example-service.jar

    run_target "${TEST_ROOT}" build-container-images.sh \
        "LIFEOS_PUSH_IMAGES=true" \
        "LIFEOS_DOCKER_TIMEOUT_SECONDS=1" \
        "FAKE_TIMEOUT_DOCKER_SUBCOMMAND=push" \
        "FAKE_TIMEOUT_STATUS=124"

    assert_status 69 "container build with a timed out Docker push"
    assert_file_contains "${RUN_OUTPUT}" \
        "Container image push for lifeos/example-service:local timed out after 1s" \
        "container image push timeout diagnostic"
    assert_log_contains "${TEST_ROOT}" $'docker\tbuild\t' \
        "container image push timeout must occur after a successful build"
    assert_log_contains "${TEST_ROOT}" \
        $'timeout\t--signal=TERM\t--kill-after=10s\t1s\tdocker push' \
        "container image push timeout wrapper"
    assert_no_logged_docker_subcommand "${TEST_ROOT}" push \
        "container image push must not invoke Docker after its timeout wrapper expires"
}

test_container_build_selects_portable_timeout_commands() {
    new_harness build-gtimeout-fallback build-container-images.sh
    add_service_dockerfile "${TEST_ROOT}" example-service
    add_service_jar "${TEST_ROOT}" example-service example-service.jar
    disable_fake_command "${TEST_ROOT}" timeout
    add_prerequisite_command "${TEST_ROOT}" bash
    add_prerequisite_command "${TEST_ROOT}" dirname
    add_prerequisite_command "${TEST_ROOT}" find
    add_prerequisite_command "${TEST_ROOT}" basename
    add_prerequisite_command "${TEST_ROOT}" sort

    run_target "${TEST_ROOT}" build-container-images.sh \
        "PATH=${TEST_ROOT}/bin:${TEST_ROOT}/prerequisite-bin"

    assert_status 0 "container build with the macOS gtimeout fallback"
    assert_log_contains "${TEST_ROOT}" \
        $'gtimeout\t--signal=TERM\t--kill-after=10s\t300s\tdocker build' \
        "container build gtimeout fallback"

    new_harness build-missing-timeout build-container-images.sh
    add_service_dockerfile "${TEST_ROOT}" example-service
    add_service_jar "${TEST_ROOT}" example-service example-service.jar
    disable_fake_command "${TEST_ROOT}" timeout
    disable_fake_command "${TEST_ROOT}" gtimeout
    add_prerequisite_command "${TEST_ROOT}" bash
    add_prerequisite_command "${TEST_ROOT}" dirname
    add_prerequisite_command "${TEST_ROOT}" find
    add_prerequisite_command "${TEST_ROOT}" basename
    add_prerequisite_command "${TEST_ROOT}" sort

    run_target "${TEST_ROOT}" build-container-images.sh \
        "PATH=${TEST_ROOT}/bin:${TEST_ROOT}/prerequisite-bin"

    assert_status 69 "container build without a timeout utility"
    assert_file_contains "${RUN_OUTPUT}" \
        "timeout (or gtimeout on macOS) is required to bound Docker operations" \
        "container build timeout utility prerequisite"
    assert_no_commands_logged "${TEST_ROOT}" \
        "container build without a timeout utility must not invoke Docker"
}

test_container_scripts_reject_invalid_generated_image_references() {
    local case_number=0
    local image_prefix image_tag expected_reference expected_output_reference
    local -a validation_cases=(
        "registry:|local|registry:/example-service:local"
        "team//api|local|team//api/example-service:local"
        "lifeos|invalid/tag|lifeos/example-service:invalid/tag"
        "[aaaa]|local|[aaaa]/example-service:local"
    )
    local validation_case

    for validation_case in "${validation_cases[@]}"; do
        IFS='|' read -r image_prefix image_tag expected_reference <<< "${validation_case}"
        ((case_number += 1))
        printf -v expected_output_reference '%q' "${expected_reference}"

        new_harness "build-invalid-image-reference-${case_number}" build-container-images.sh
        add_service_dockerfile "${TEST_ROOT}" example-service
        add_service_jar "${TEST_ROOT}" example-service example-service.jar

        run_target "${TEST_ROOT}" build-container-images.sh \
            "LIFEOS_IMAGE_PREFIX=${image_prefix}" \
            "LIFEOS_IMAGE_TAG=${image_tag}"

        assert_status 64 "container build with invalid generated reference ${expected_reference}"
        assert_file_contains "${RUN_OUTPUT}" "Invalid container image reference ${expected_output_reference}" \
            "container build image-reference validation ${expected_reference}"
        assert_no_commands_logged "${TEST_ROOT}" \
            "container build with invalid reference ${expected_reference} must not invoke Docker"

        new_harness "scan-invalid-image-reference-${case_number}" scan-container-images.sh
        add_service_dockerfile "${TEST_ROOT}" example-service

        run_target "${TEST_ROOT}" scan-container-images.sh \
            "LIFEOS_IMAGE_PREFIX=${image_prefix}" \
            "LIFEOS_IMAGE_TAG=${image_tag}" \
            "LIFEOS_TRIVY_CACHE_DIR=${TEST_ROOT}/trivy-cache" \
            "FAKE_DOCKER_IMAGE_INSPECT_STATUS=1"

        assert_status 64 "container scan with invalid generated reference ${expected_reference}"
        assert_file_contains "${RUN_OUTPUT}" "Invalid container image reference ${expected_output_reference}" \
            "container scan image-reference validation ${expected_reference}"
        assert_no_commands_logged "${TEST_ROOT}" \
            "container scan with invalid reference ${expected_reference} must not invoke Docker"
    done
}

test_container_scripts_enforce_docker_repository_name_length() {
    local overlength_component
    local overlength_image_prefix
    local expected_reference expected_output_reference
    local registry_label long_registry image_prefix

    printf -v overlength_component '%*s' 256 ''
    overlength_component="${overlength_component// /a}"
    # The registry port ensures the validator strips the final tag colon rather than the port
    # colon before measuring Docker's repository path.
    overlength_image_prefix="registry.example:5000/${overlength_component}"
    expected_reference="${overlength_image_prefix}/example-service:local"
    printf -v expected_output_reference '%q' "${expected_reference}"

    new_harness build-overlength-repository-path build-container-images.sh
    add_service_dockerfile "${TEST_ROOT}" example-service
    add_service_jar "${TEST_ROOT}" example-service example-service.jar

    run_target "${TEST_ROOT}" build-container-images.sh \
        "LIFEOS_IMAGE_PREFIX=${overlength_image_prefix}"

    assert_status 64 "container build with an overlength repository path"
    assert_file_contains "${RUN_OUTPUT}" "Invalid container image reference ${expected_output_reference}" \
        "container build overlength repository-path validation"
    assert_no_commands_logged "${TEST_ROOT}" \
        "container build with an overlength repository path must not invoke Docker"

    new_harness scan-overlength-repository-path scan-container-images.sh
    add_service_dockerfile "${TEST_ROOT}" example-service

    run_target "${TEST_ROOT}" scan-container-images.sh \
        "LIFEOS_IMAGE_PREFIX=${overlength_image_prefix}" \
        "LIFEOS_TRIVY_CACHE_DIR=${TEST_ROOT}/trivy-cache"

    assert_status 64 "container scan with an overlength repository path"
    assert_file_contains "${RUN_OUTPUT}" "Invalid container image reference ${expected_output_reference}" \
        "container scan overlength repository-path validation"
    assert_no_commands_logged "${TEST_ROOT}" \
        "container scan with an overlength repository path must not invoke Docker"

    # Four 60-character labels make the complete repository name exceed 255 characters while
    # retaining a syntactically valid DNS-style registry and a short service name. The tag is
    # deliberately excluded from the 255-character repository-name limit.
    printf -v registry_label '%*s' 60 ''
    registry_label="${registry_label// /r}"
    long_registry="${registry_label}.${registry_label}.${registry_label}.${registry_label}"
    image_prefix="${long_registry}/lifeos"
    expected_reference="${image_prefix}/example-service:local"
    printf -v expected_output_reference '%q' "${expected_reference}"

    new_harness build-overlength-registry build-container-images.sh
    add_service_dockerfile "${TEST_ROOT}" example-service
    add_service_jar "${TEST_ROOT}" example-service example-service.jar

    run_target "${TEST_ROOT}" build-container-images.sh \
        "LIFEOS_IMAGE_PREFIX=${image_prefix}"

    assert_status 64 "container build with an overlength registry"
    assert_file_contains "${RUN_OUTPUT}" "Invalid container image reference ${expected_output_reference}" \
        "container build overlength-registry validation"
    assert_no_commands_logged "${TEST_ROOT}" \
        "container build with an overlength registry must not invoke Docker"

    new_harness scan-overlength-registry scan-container-images.sh
    add_service_dockerfile "${TEST_ROOT}" example-service

    run_target "${TEST_ROOT}" scan-container-images.sh \
        "LIFEOS_IMAGE_PREFIX=${image_prefix}" \
        "LIFEOS_TRIVY_CACHE_DIR=${TEST_ROOT}/trivy-cache"

    assert_status 64 "container scan with an overlength registry"
    assert_file_contains "${RUN_OUTPUT}" "Invalid container image reference ${expected_output_reference}" \
        "container scan overlength-registry validation"
    assert_no_commands_logged "${TEST_ROOT}" \
        "container scan with an overlength registry must not invoke Docker"
}

test_container_scan_rejects_missing_images_and_passes_trivy_arguments() {
    new_harness scan-container-missing scan-container-images.sh
    add_service_dockerfile "${TEST_ROOT}" example-service
    local cache_dir="${TEST_ROOT}/trivy-cache"

    run_target "${TEST_ROOT}" scan-container-images.sh \
        "LIFEOS_IMAGE_TAG=scan-42" \
        "LIFEOS_TRIVY_CACHE_DIR=${cache_dir}" \
        "FAKE_DOCKER_IMAGE_INSPECT_STATUS=1"

    assert_status 66 "container scan with a missing image"
    assert_file_contains "${RUN_OUTPUT}" "Container image lifeos/example-service:scan-42 is missing" "container scan with a missing image"
    assert_log_contains "${TEST_ROOT}" \
        $'docker\timage\tinspect\tlifeos/example-service:scan-42' \
        "container image inspection"
    assert_log_order "${TEST_ROOT}" \
        $'docker\tinfo' \
        $'docker\timage\tinspect\tlifeos/example-service:scan-42' \
        "container scan daemon preflight ordering"
    assert_log_excludes "${TEST_ROOT}" $'docker\trun\t' "container scan after a missing image"

    new_harness scan-container-arguments scan-container-images.sh
    add_service_dockerfile "${TEST_ROOT}" example-service
    cache_dir="${TEST_ROOT}/trivy-cache"

    run_target "${TEST_ROOT}" scan-container-images.sh \
        "LIFEOS_IMAGE_TAG=scan-42" \
        "LIFEOS_TRIVY_CACHE_DIR=${cache_dir}"

    assert_status 0 "container scan with an available image"
    assert_log_contains "${TEST_ROOT}" \
        $'docker\trun\t--rm\t--mount\ttype=bind,"source='"${cache_dir}"$'",target=/root/.cache\t--volume\t/var/run/docker.sock:/var/run/docker.sock' \
        "container scan mounts"
    assert_log_contains "${TEST_ROOT}" \
        $'\timage\t--no-progress\t--exit-code\t1\t--ignore-unfixed\t--severity\tHIGH,CRITICAL\tlifeos/example-service:scan-42' \
        "container scan Trivy image arguments"
}

test_container_scan_uses_a_csv_quoted_mount_for_comma_cache_paths() {
    new_harness scan-container-comma-cache scan-container-images.sh
    add_service_dockerfile "${TEST_ROOT}" example-service

    local cache_dir="${TEST_ROOT}/trivy,cache"
    run_target "${TEST_ROOT}" scan-container-images.sh \
        "LIFEOS_TRIVY_CACHE_DIR=${cache_dir}"

    assert_status 0 "container scan with a comma-containing Trivy cache path"
    assert_log_contains "${TEST_ROOT}" \
        $'docker\trun\t--rm\t--mount\ttype=bind,"source='"${cache_dir}"$'",target=/root/.cache\t--volume\t/var/run/docker.sock:/var/run/docker.sock' \
        "container scan comma-path cache mount"
}

test_container_scan_requires_an_accessible_docker_daemon() {
    new_harness scan-container-unavailable-daemon scan-container-images.sh
    add_service_dockerfile "${TEST_ROOT}" example-service

    run_target "${TEST_ROOT}" scan-container-images.sh \
        "LIFEOS_TRIVY_CACHE_DIR=${TEST_ROOT}/trivy-cache" \
        "FAKE_DOCKER_INFO_STATUS=1"

    assert_status 69 "container scan with an unavailable Docker daemon"
    assert_file_contains "${RUN_OUTPUT}" \
        "Docker daemon is unavailable or inaccessible" \
        "container scan Docker daemon preflight"
    assert_log_contains "${TEST_ROOT}" $'docker\tinfo' "container scan Docker daemon preflight command"
    assert_log_excludes "${TEST_ROOT}" $'docker\timage\tinspect\t' \
        "container scan after an unavailable Docker daemon"
    assert_log_excludes "${TEST_ROOT}" $'docker\trun\t' \
        "container scan after an unavailable Docker daemon"
}

test_source_scan_uses_read_only_repository_mount_and_filesystem_arguments() {
    new_harness scan-source-arguments scan-source-security.sh
    local cache_dir="${TEST_ROOT}/trivy-cache"

    run_target "${TEST_ROOT}" scan-source-security.sh "LIFEOS_TRIVY_CACHE_DIR=${cache_dir}"

    assert_status 0 "source security scan"
    assert_log_contains "${TEST_ROOT}" \
        $'docker\trun\t--rm\t--mount\ttype=bind,"source='"${cache_dir}"$'",target=/root/.cache\t--mount\ttype=bind,"source='"${TEST_ROOT}"$'",target=/repo,readonly\t--workdir\t/repo' \
        "source security scan mounts"
    assert_log_contains "${TEST_ROOT}" \
        $'\tfs\t--no-progress\t--exit-code\t1\t--ignore-unfixed\t--scanners\tvuln,secret,misconfig\t--severity\tHIGH,CRITICAL\t--skip-dirs\t.git\t--skip-dirs\t.gradle\t.' \
        "source security scan Trivy filesystem arguments"
    assert_log_order "${TEST_ROOT}" \
        $'docker\tinfo' \
        $'docker\trun\t' \
        "source security scan daemon preflight ordering"
}

test_source_scan_uses_an_unambiguous_read_only_mount_for_colon_repository_paths() {
    new_harness scan-source-colon-path scan-source-security.sh

    local colon_repository_root="${TEST_ROOT}/repository:source"
    local cache_dir="${colon_repository_root}/trivy-cache"
    mkdir -p "${colon_repository_root}/scripts"
    cp "${TEST_ROOT}/scripts/scan-source-security.sh" \
        "${colon_repository_root}/scripts/scan-source-security.sh"

    # Keep command doubles on a colon-free PATH entry while passing an executable path whose
    # computed repository root contains a colon. Docker's long --mount form keeps this source as
    # one explicit field rather than applying legacy --volume colon splitting.
    run_target "${TEST_ROOT}" "../repository:source/scripts/scan-source-security.sh" \
        "LIFEOS_TRIVY_CACHE_DIR=${cache_dir}"

    assert_status 0 "source security scan from a colon-containing repository path"
    assert_log_contains "${TEST_ROOT}" \
        $'docker\trun\t--rm\t--mount\ttype=bind,"source='"${cache_dir}"$'",target=/root/.cache\t--mount\ttype=bind,"source='"${colon_repository_root}"$'",target=/repo,readonly\t--workdir\t/repo' \
        "source security scan colon-path repository mount"
    assert_log_entry_excludes "${TEST_ROOT}" \
        $'docker\trun\t' \
        $'--volume\t' \
        "source security scan must not use ambiguous volume syntax for a colon-containing repository path"
}

test_source_scan_uses_a_csv_quoted_mount_for_comma_repository_paths() {
    new_harness scan-source-comma-path scan-source-security.sh

    local comma_repository_root="${TEST_ROOT}/repository,source"
    local cache_dir="${TEST_ROOT}/trivy-cache"
    mkdir -p "${comma_repository_root}/scripts"
    cp "${TEST_ROOT}/scripts/scan-source-security.sh" \
        "${comma_repository_root}/scripts/scan-source-security.sh"

    # Docker parses --mount parameters as CSV. Keep the cache path comma-free so this assertion
    # proves the computed repository root is encoded as one source= field rather than an option.
    run_target "${TEST_ROOT}" "../repository,source/scripts/scan-source-security.sh" \
        "LIFEOS_TRIVY_CACHE_DIR=${cache_dir}"

    assert_status 0 "source security scan from a comma-containing repository path"
    assert_log_contains "${TEST_ROOT}" \
        $'docker\trun\t--rm\t--mount\ttype=bind,"source='"${cache_dir}"$'",target=/root/.cache\t--mount\ttype=bind,"source='"${comma_repository_root}"$'",target=/repo,readonly\t--workdir\t/repo' \
        "source security scan comma-path repository mount"
    assert_log_entry_excludes "${TEST_ROOT}" \
        $'docker\trun\t' \
        $'--volume\t' \
        "source security scan must retain long mount syntax for a comma-containing repository path"
}

test_source_scan_requires_an_accessible_docker_daemon() {
    new_harness scan-source-unavailable-daemon scan-source-security.sh

    run_target "${TEST_ROOT}" scan-source-security.sh \
        "LIFEOS_TRIVY_CACHE_DIR=${TEST_ROOT}/trivy-cache" \
        "FAKE_DOCKER_INFO_STATUS=1"

    assert_status 69 "source security scan with an unavailable Docker daemon"
    assert_file_contains "${RUN_OUTPUT}" \
        "Docker daemon is unavailable or inaccessible" \
        "source security scan Docker daemon preflight"
    assert_log_contains "${TEST_ROOT}" $'docker\tinfo' \
        "source security scan Docker daemon preflight command"
    assert_log_excludes "${TEST_ROOT}" $'docker\trun\t' \
        "source security scan after an unavailable Docker daemon"
}

test_security_scans_require_an_absolute_trivy_cache_directory() {
    local security_scan_script

    for security_scan_script in scan-container-images.sh scan-source-security.sh; do
        new_harness "${security_scan_script%.sh}-relative-trivy-cache" "${security_scan_script}"
        if [[ "${security_scan_script}" == "scan-container-images.sh" ]]; then
            add_service_dockerfile "${TEST_ROOT}" example-service
        fi

        run_target "${TEST_ROOT}" "${security_scan_script}" \
            "LIFEOS_TRIVY_CACHE_DIR=relative-trivy-cache"

        assert_status 64 "${security_scan_script} with a relative Trivy cache directory"
        assert_file_contains "${RUN_OUTPUT}" \
            "LIFEOS_TRIVY_CACHE_DIR must be an absolute path" \
            "${security_scan_script} relative Trivy cache diagnostic"
        assert_no_commands_logged "${TEST_ROOT}" \
            "${security_scan_script} with a relative Trivy cache directory must not invoke Docker"
    done
}

test_security_scans_validate_and_bound_docker_operations() {
    local security_scan_script
    local invalid_timeout
    local cache_dir

    for security_scan_script in scan-container-images.sh scan-source-security.sh; do
        for invalid_timeout in 0 901 nonnumeric; do
            new_harness "${security_scan_script%.sh}-invalid-docker-timeout-${invalid_timeout}" "${security_scan_script}"
            if [[ "${security_scan_script}" == "scan-container-images.sh" ]]; then
                add_service_dockerfile "${TEST_ROOT}" example-service
            fi

            run_target "${TEST_ROOT}" "${security_scan_script}" \
                "LIFEOS_TRIVY_CACHE_DIR=${TEST_ROOT}/trivy-cache" \
                "LIFEOS_DOCKER_TIMEOUT_SECONDS=${invalid_timeout}"

            assert_status 64 "${security_scan_script} with invalid Docker timeout ${invalid_timeout}"
            assert_file_contains "${RUN_OUTPUT}" \
                "LIFEOS_DOCKER_TIMEOUT_SECONDS must be between 1 and 900 seconds" \
                "${security_scan_script} invalid Docker-timeout diagnostic"
            assert_no_commands_logged "${TEST_ROOT}" \
                "${security_scan_script} with invalid Docker timeout must not invoke Docker"
        done

        new_harness "${security_scan_script%.sh}-docker-info-timeout" "${security_scan_script}"
        if [[ "${security_scan_script}" == "scan-container-images.sh" ]]; then
            add_service_dockerfile "${TEST_ROOT}" example-service
        fi

        run_target "${TEST_ROOT}" "${security_scan_script}" \
            "LIFEOS_TRIVY_CACHE_DIR=${TEST_ROOT}/trivy-cache" \
            "LIFEOS_DOCKER_TIMEOUT_SECONDS=1" \
            "FAKE_TIMEOUT_DOCKER_SUBCOMMAND=info" \
            "FAKE_TIMEOUT_STATUS=124"

        assert_status 69 "${security_scan_script} with a timed out Docker daemon check"
        assert_file_contains "${RUN_OUTPUT}" "Docker daemon check timed out after 1s" \
            "${security_scan_script} Docker daemon timeout diagnostic"
        assert_log_contains "${TEST_ROOT}" \
            $'timeout\t--signal=TERM\t--kill-after=10s\t1s\tdocker info' \
            "${security_scan_script} Docker daemon timeout wrapper"
        assert_no_logged_command "${TEST_ROOT}" docker \
            "${security_scan_script} must not invoke Docker after its timeout wrapper expires"

        new_harness "${security_scan_script%.sh}-trivy-run-timeout" "${security_scan_script}"
        if [[ "${security_scan_script}" == "scan-container-images.sh" ]]; then
            add_service_dockerfile "${TEST_ROOT}" example-service
        fi
        cache_dir="${TEST_ROOT}/trivy-cache"

        run_target "${TEST_ROOT}" "${security_scan_script}" \
            "LIFEOS_TRIVY_CACHE_DIR=${cache_dir}" \
            "LIFEOS_DOCKER_TIMEOUT_SECONDS=1" \
            "FAKE_TIMEOUT_DOCKER_SUBCOMMAND=run" \
            "FAKE_TIMEOUT_STATUS=124"

        assert_status 69 "${security_scan_script} with a timed out Trivy invocation"
        if [[ "${security_scan_script}" == "scan-container-images.sh" ]]; then
            assert_file_contains "${RUN_OUTPUT}" \
                "Trivy image scan for lifeos/example-service:local timed out after 1s" \
                "container scan Trivy timeout diagnostic"
        else
            assert_file_contains "${RUN_OUTPUT}" "Trivy source security scan timed out after 1s" \
                "source scan Trivy timeout diagnostic"
        fi
        assert_log_contains "${TEST_ROOT}" \
            $'timeout\t--signal=TERM\t--kill-after=10s\t1s\tdocker run' \
            "${security_scan_script} Trivy timeout wrapper"
        assert_no_logged_docker_subcommand "${TEST_ROOT}" run \
            "${security_scan_script} must not invoke Trivy after its timeout wrapper expires"
    done

    new_harness scan-container-image-inspect-timeout scan-container-images.sh
    add_service_dockerfile "${TEST_ROOT}" example-service

    run_target "${TEST_ROOT}" scan-container-images.sh \
        "LIFEOS_TRIVY_CACHE_DIR=${TEST_ROOT}/trivy-cache" \
        "LIFEOS_DOCKER_TIMEOUT_SECONDS=1" \
        "FAKE_TIMEOUT_DOCKER_SUBCOMMAND=image" \
        "FAKE_TIMEOUT_STATUS=124"

    assert_status 69 "container scan with a timed out image inspection"
    assert_file_contains "${RUN_OUTPUT}" \
        "Container image availability check for lifeos/example-service:local timed out after 1s" \
        "container scan image-inspection timeout diagnostic"
    assert_log_contains "${TEST_ROOT}" \
        $'timeout\t--signal=TERM\t--kill-after=10s\t1s\tdocker image' \
        "container scan image-inspection timeout wrapper"
    assert_log_excludes "${TEST_ROOT}" $'docker\trun\t' \
        "container scan must not start Trivy after a timed out image inspection"
}

test_security_scans_select_portable_timeout_commands() {
    local security_scan_script

    for security_scan_script in scan-container-images.sh scan-source-security.sh; do
        new_harness "${security_scan_script%.sh}-gtimeout-fallback" "${security_scan_script}"
        if [[ "${security_scan_script}" == "scan-container-images.sh" ]]; then
            add_service_dockerfile "${TEST_ROOT}" example-service
        fi
        disable_fake_command "${TEST_ROOT}" timeout
        add_prerequisite_command "${TEST_ROOT}" bash
        add_prerequisite_command "${TEST_ROOT}" dirname
        add_prerequisite_command "${TEST_ROOT}" mkdir
        add_prerequisite_command "${TEST_ROOT}" rmdir
        add_prerequisite_command "${TEST_ROOT}" sleep
        if [[ "${security_scan_script}" == "scan-container-images.sh" ]]; then
            add_prerequisite_command "${TEST_ROOT}" find
            add_prerequisite_command "${TEST_ROOT}" basename
            add_prerequisite_command "${TEST_ROOT}" sort
        fi

        run_target "${TEST_ROOT}" "${security_scan_script}" \
            "LIFEOS_TRIVY_CACHE_DIR=${TEST_ROOT}/trivy-cache" \
            "PATH=${TEST_ROOT}/bin:${TEST_ROOT}/prerequisite-bin"

        assert_status 0 "${security_scan_script} with the macOS gtimeout fallback"
        assert_log_contains "${TEST_ROOT}" \
            $'gtimeout\t--signal=TERM\t--kill-after=10s\t300s\tdocker info' \
            "${security_scan_script} gtimeout fallback"
    done

    new_harness scan-source-missing-timeout scan-source-security.sh
    disable_fake_command "${TEST_ROOT}" timeout
    disable_fake_command "${TEST_ROOT}" gtimeout
    add_prerequisite_command "${TEST_ROOT}" bash
    add_prerequisite_command "${TEST_ROOT}" dirname

    run_target "${TEST_ROOT}" scan-source-security.sh \
        "PATH=${TEST_ROOT}/bin:${TEST_ROOT}/prerequisite-bin"

    assert_status 69 "source security scan without a timeout utility"
    assert_file_contains "${RUN_OUTPUT}" \
        "timeout (or gtimeout on macOS) is required to bound Docker operations" \
        "source scan timeout utility prerequisite"
    assert_no_commands_logged "${TEST_ROOT}" \
        "source security scan without a timeout utility must not invoke Docker"
}

test_security_scans_serialize_shared_trivy_cache_access() {
    new_harness scan-shared-trivy-cache scan-container-images.sh scan-source-security.sh
    add_service_dockerfile "${TEST_ROOT}" example-service

    local cache_dir="${TEST_ROOT}/trivy-cache"
    local lock_directory="${cache_dir}/.lifeos-trivy-cache.lock"
    local first_started_file="${TEST_ROOT}/container-scan-started"
    local release_file="${TEST_ROOT}/release-container-scan"
    local container_output="${TEST_ROOT}/container-scan-output.log"
    local source_output="${TEST_ROOT}/source-scan-output.log"
    local container_pid source_pid
    local container_status source_status
    local docker_runs_while_locked

    : > "${TEST_ROOT}/commands.log"
    start_target "${TEST_ROOT}" scan-container-images.sh "${container_output}" \
        "LIFEOS_TRIVY_CACHE_DIR=${cache_dir}" \
        "FAKE_DOCKER_RUN_STARTED_FILE=${first_started_file}" \
        "FAKE_DOCKER_RUN_RELEASE_FILE=${release_file}" \
        "FAKE_SLEEP_REAL_DELAY=0.05"
    container_pid="${BACKGROUND_TARGET_PID}"

    if ! wait_for_log_line_count "${TEST_ROOT}" $'docker\trun\t' 1; then
        : > "${release_file}"
        wait "${container_pid}" || true
        fail "container scan did not reach its Trivy invocation"
    fi
    if ! wait_for_file "${first_started_file}" || [[ ! -d "${lock_directory}" ]]; then
        : > "${release_file}"
        wait "${container_pid}" || true
        fail "container scan must hold the configured Trivy cache lock while Trivy runs"
    fi

    start_target "${TEST_ROOT}" scan-source-security.sh "${source_output}" \
        "LIFEOS_TRIVY_CACHE_DIR=${cache_dir}" \
        "FAKE_DOCKER_RUN_STARTED_FILE=${first_started_file}" \
        "FAKE_DOCKER_RUN_RELEASE_FILE=${release_file}" \
        "FAKE_SLEEP_REAL_DELAY=0.05"
    source_pid="${BACKGROUND_TARGET_PID}"

    # The source scan's retry proves it reached the shared lock while the image scan owns it. If
    # the scripts mounted the cache without coordination, it would issue a second Docker run here.
    if ! wait_for_log_line_count "${TEST_ROOT}" $'sleep\t' 1; then
        : > "${release_file}"
        wait "${container_pid}" || true
        wait "${source_pid}" || true
        fail "source scan did not wait for the shared Trivy cache lock"
    fi
    docker_runs_while_locked="$(grep -F -c -- $'docker\trun\t' "${TEST_ROOT}/commands.log" || true)"

    : > "${release_file}"
    if wait "${container_pid}"; then
        container_status=0
    else
        container_status=$?
    fi
    if wait "${source_pid}"; then
        source_status=0
    else
        source_status=$?
    fi

    if [[ "${container_status}" -ne 0 || "${source_status}" -ne 0 ]]; then
        fail "shared Trivy cache scans must complete after the lock is released"
    fi
    if [[ "${docker_runs_while_locked}" -ne 1 ]]; then
        fail "shared Trivy cache lock must permit only one concurrent Trivy invocation"
    fi
    if [[ -e "${lock_directory}" ]]; then
        fail "shared Trivy cache lock must be released after both scans complete"
    fi
    assert_log_line_count "${TEST_ROOT}" $'docker\trun\t' 2 \
        "shared Trivy cache scans after lock release"
    assert_log_line_count "${TEST_ROOT}" \
        $'--mount\ttype=bind,"source='"${cache_dir}"$'",target=/root/.cache' 2 \
        "shared Trivy cache mount paths"
    assert_log_contains "${TEST_ROOT}" \
        $'--mount\ttype=bind,"source='"${cache_dir}"$'",target=/root/.cache\t--volume\t/var/run/docker.sock:/var/run/docker.sock' \
        "container scan shared Trivy cache mount"
    assert_log_contains "${TEST_ROOT}" \
        $'--mount\ttype=bind,"source='"${cache_dir}"$'",target=/root/.cache\t--mount\ttype=bind,"source='"${TEST_ROOT}"$'",target=/repo,readonly' \
        "source scan shared Trivy cache mount"
}

test_container_scan_releases_trivy_cache_lock_between_services() {
    local cache_dir lock_directory line state=0

    new_harness scan-container-per-image-lock scan-container-images.sh
    add_service_dockerfile "${TEST_ROOT}" service-a
    add_service_dockerfile "${TEST_ROOT}" service-b
    add_mkdir_double "${TEST_ROOT}"
    add_rmdir_double "${TEST_ROOT}"

    cache_dir="${TEST_ROOT}/trivy-cache"
    lock_directory="${cache_dir}/.lifeos-trivy-cache.lock"
    run_target "${TEST_ROOT}" scan-container-images.sh \
        "LIFEOS_TRIVY_CACHE_DIR=${cache_dir}"

    assert_status 0 "container scan with multiple services and a per-image cache lock"
    assert_log_line_count "${TEST_ROOT}" $'docker\trun\t' 2 \
        "container scan with multiple services must run both Trivy scans"
    assert_log_line_count "${TEST_ROOT}" $'mkdir\t'"${lock_directory}" 2 \
        "container scan must acquire the cache lock for each image"
    assert_log_line_count "${TEST_ROOT}" $'rmdir\t'"${lock_directory}" 2 \
        "container scan must release the cache lock for each image"

    # Walk the command log to prove that the first lock is released before the second scan starts.
    # This fails deterministically if the lock is held around the whole SERVICES loop.
    while IFS= read -r line; do
        case "${state}:${line}" in
            0:mkdir$'\t'"${lock_directory}") state=1 ;;
            1:docker$'\trun\t'*) state=2 ;;
            2:rmdir$'\t'"${lock_directory}") state=3 ;;
            3:mkdir$'\t'"${lock_directory}") state=4 ;;
            4:docker$'\trun\t'*) state=5 ;;
            5:rmdir$'\t'"${lock_directory}") state=6 ;;
        esac
    done < "${TEST_ROOT}/commands.log"
    if [[ "${state}" -ne 6 ]]; then
        fail "container scan must release its Trivy cache lock between service scans"
    fi
    if [[ -e "${lock_directory}" ]]; then
        fail "container scan must leave no Trivy cache lock after all service scans"
    fi
}

test_security_scans_fail_fast_when_the_trivy_cache_lock_cannot_be_created() {
    local security_scan_script
    local cache_dir
    local lock_directory

    for security_scan_script in scan-container-images.sh scan-source-security.sh; do
        new_harness "${security_scan_script%.sh}-cache-lock-create-failure" "${security_scan_script}"
        if [[ "${security_scan_script}" == "scan-container-images.sh" ]]; then
            add_service_dockerfile "${TEST_ROOT}" example-service
        fi
        add_mkdir_double "${TEST_ROOT}"

        cache_dir="${TEST_ROOT}/trivy-cache"
        lock_directory="${cache_dir}/.lifeos-trivy-cache.lock"
        run_target "${TEST_ROOT}" "${security_scan_script}" \
            "LIFEOS_TRIVY_CACHE_DIR=${cache_dir}" \
            "FAKE_MKDIR_FAILURE_PATH=${lock_directory}" \
            "FAKE_MKDIR_FAILURE_STATUS=13"

        assert_status 69 "${security_scan_script} with an uncreatable Trivy cache lock"
        assert_file_contains "${RUN_OUTPUT}" \
            "Unable to acquire exclusive access to the Trivy cache" \
            "${security_scan_script} cache-lock creation diagnostic"
        assert_file_contains "${RUN_OUTPUT}" \
            "fake mkdir forced failure for ${lock_directory}" \
            "${security_scan_script} cache-lock creation preserves the mkdir diagnostic"
        assert_log_line_count "${TEST_ROOT}" $'mkdir\t'"${lock_directory}" 2 \
            "${security_scan_script} cache-lock creation attempts"
        assert_log_excludes "${TEST_ROOT}" $'sleep\t' \
            "${security_scan_script} must not wait after a non-lock mkdir failure"
        assert_log_excludes "${TEST_ROOT}" $'docker\trun\t' \
            "${security_scan_script} must not start Trivy after a cache-lock creation failure"
    done
}

test_security_scans_reject_symlinked_trivy_cache_locks_without_waiting() {
    local security_scan_script
    local cache_dir
    local lock_directory

    for security_scan_script in scan-container-images.sh scan-source-security.sh; do
        new_harness "${security_scan_script%.sh}-symlinked-cache-lock" "${security_scan_script}"
        if [[ "${security_scan_script}" == "scan-container-images.sh" ]]; then
            add_service_dockerfile "${TEST_ROOT}" example-service
        fi

        cache_dir="${TEST_ROOT}/trivy-cache"
        lock_directory="${cache_dir}/.lifeos-trivy-cache.lock"
        mkdir -p "${cache_dir}"
        ln -s "${TEST_ROOT}" "${lock_directory}"

        run_target "${TEST_ROOT}" "${security_scan_script}" \
            "LIFEOS_TRIVY_CACHE_DIR=${cache_dir}"

        assert_status 69 "${security_scan_script} with a symlinked Trivy cache lock"
        assert_file_contains "${RUN_OUTPUT}" \
            "Unable to acquire exclusive access to the Trivy cache" \
            "${security_scan_script} symlinked cache-lock diagnostic"
        assert_log_excludes "${TEST_ROOT}" $'sleep\t' \
            "${security_scan_script} must not wait for a symlinked cache lock"
        assert_log_excludes "${TEST_ROOT}" $'docker\trun\t' \
            "${security_scan_script} must not start Trivy with a symlinked cache lock"
    done
}

test_security_scans_retry_after_a_trivy_cache_lock_release_race() {
    local security_scan_script
    local cache_dir
    local lock_directory
    local first_failure_file

    for security_scan_script in scan-container-images.sh scan-source-security.sh; do
        new_harness "${security_scan_script%.sh}-cache-lock-release-race" "${security_scan_script}"
        if [[ "${security_scan_script}" == "scan-container-images.sh" ]]; then
            add_service_dockerfile "${TEST_ROOT}" example-service
        fi
        add_mkdir_double "${TEST_ROOT}"

        cache_dir="${TEST_ROOT}/trivy-cache"
        lock_directory="${cache_dir}/.lifeos-trivy-cache.lock"
        first_failure_file="${TEST_ROOT}/first-cache-lock-mkdir-failure"
        run_target "${TEST_ROOT}" "${security_scan_script}" \
            "LIFEOS_TRIVY_CACHE_DIR=${cache_dir}" \
            "FAKE_MKDIR_FAILURE_PATH=${lock_directory}" \
            "FAKE_MKDIR_FAILURE_ONCE_FILE=${first_failure_file}"

        assert_status 0 "${security_scan_script} after a Trivy cache-lock release race"
        if [[ ! -f "${first_failure_file}" ]]; then
            fail "${security_scan_script} must exercise its transient cache-lock acquisition retry"
        fi
        assert_log_line_count "${TEST_ROOT}" $'mkdir\t'"${lock_directory}" 2 \
            "${security_scan_script} transient cache-lock acquisition attempts"
        assert_log_excludes "${TEST_ROOT}" $'sleep\t' \
            "${security_scan_script} must immediately reacquire a released cache lock"
        assert_log_contains "${TEST_ROOT}" $'docker\trun\t' \
            "${security_scan_script} after a released cache lock"
    done
}

test_security_scans_ignore_untrusted_trivy_image_overrides() {
    local trusted_trivy_image="aquasec/trivy:0.67.0@sha256:94711c60051c6cab848a292e3a67f62623fcee361b2bb661f43b17184f4afdac"
    local untrusted_trivy_image="registry.example.test/untrusted-trivy:latest"

    new_harness scan-container-trusted-image scan-container-images.sh
    add_service_dockerfile "${TEST_ROOT}" example-service

    run_target "${TEST_ROOT}" scan-container-images.sh \
        "LIFEOS_TRIVY_CACHE_DIR=${TEST_ROOT}/trivy-cache" \
        "LIFEOS_TRIVY_IMAGE=${untrusted_trivy_image}"

    assert_status 0 "container scan with an untrusted Trivy image override"
    assert_log_contains "${TEST_ROOT}" \
        $'\t'"${trusted_trivy_image}"$'\timage\t' \
        "container scan trusted Trivy image"
    assert_log_excludes "${TEST_ROOT}" "${untrusted_trivy_image}" \
        "container scan untrusted Trivy image override"

    new_harness scan-source-trusted-image scan-source-security.sh

    run_target "${TEST_ROOT}" scan-source-security.sh \
        "LIFEOS_TRIVY_CACHE_DIR=${TEST_ROOT}/trivy-cache" \
        "LIFEOS_TRIVY_IMAGE=${untrusted_trivy_image}"

    assert_status 0 "source security scan with an untrusted Trivy image override"
    assert_log_contains "${TEST_ROOT}" \
        $'\t'"${trusted_trivy_image}"$'\tfs\t' \
        "source security scan trusted Trivy image"
    assert_log_excludes "${TEST_ROOT}" "${untrusted_trivy_image}" \
        "source security scan untrusted Trivy image override"
}

test_database_provisioning_waits_before_exec_and_handles_failures() {
    new_harness provision-success provision-local-databases.sh
    add_database_provisioning_sql "${TEST_ROOT}"

    run_target "${TEST_ROOT}" provision-local-databases.sh \
        "FAKE_DOCKER_STDIN_LOG=${TEST_ROOT}/executed-provisioning.sql"

    assert_status 0 "database provisioning after healthy Compose startup"
    local compose_file="${TEST_ROOT}/infrastructure/docker-compose/docker-compose.yml"
    assert_log_contains "${TEST_ROOT}" \
        $'docker\tcompose\t-f\t'"${compose_file}"$'\tup\t--detach\t--wait\t--wait-timeout\t60\tpostgres' \
        "database Compose health wait"
    assert_log_contains "${TEST_ROOT}" \
        $'docker\tcompose\t-f\t'"${compose_file}"$'\texec\t-T\tpostgres\tsh\t-ec' \
        "database provisioning exec"
    assert_log_order "${TEST_ROOT}" \
        $'docker\tcompose\t-f\t'"${compose_file}"$'\tup\t--detach\t--wait' \
        $'docker\tcompose\t-f\t'"${compose_file}"$'\texec\t-T\tpostgres' \
        "database provisioning startup ordering"
    assert_file_contains "${TEST_ROOT}/executed-provisioning.sql" \
        "CREATE DATABASE %I', 'lifeos_identity'" \
        "database provisioning identity SQL payload"
    assert_file_contains "${TEST_ROOT}/executed-provisioning.sql" \
        "CREATE DATABASE %I', 'lifeos_task_goal'" \
        "database provisioning task-goal SQL payload"
    assert_file_contains "${TEST_ROOT}/executed-provisioning.sql" \
        $'WHERE datname = \'lifeos_identity\'\n)\n\\gexec' \
        "database provisioning psql gexec payload"

    new_harness provision-request-failure provision-local-databases.sh
    add_database_provisioning_sql "${TEST_ROOT}"

    run_target "${TEST_ROOT}" provision-local-databases.sh \
        "FAKE_DOCKER_COMPOSE_UP_STATUS=17" \
        "FAKE_DOCKER_COMPOSE_UP_MESSAGE=Compose request failed"

    assert_nonzero_status "database provisioning when Compose cannot start postgres"
    assert_log_excludes "${TEST_ROOT}" $'\texec\t-T\tpostgres' "database provisioning after Compose request failure"

    new_harness provision-timeout provision-local-databases.sh
    add_database_provisioning_sql "${TEST_ROOT}"

    run_target "${TEST_ROOT}" provision-local-databases.sh \
        "LIFEOS_DATABASE_PROVISION_TIMEOUT_SECONDS=1" \
        "FAKE_DOCKER_COMPOSE_UP_STATUS=1" \
        "FAKE_DOCKER_COMPOSE_UP_MESSAGE=Timed out waiting for postgres health"

    assert_nonzero_status "database provisioning when the health wait times out"
    assert_log_contains "${TEST_ROOT}" \
        $'\tup\t--detach\t--wait\t--wait-timeout\t1\tpostgres' \
        "database provisioning timeout bound"
    assert_log_excludes "${TEST_ROOT}" $'\texec\t-T\tpostgres' "database provisioning after a health timeout"
}

test_database_provisioning_requires_the_compose_plugin() {
    new_harness provision-missing-compose-plugin provision-local-databases.sh
    add_database_provisioning_sql "${TEST_ROOT}"

    run_target "${TEST_ROOT}" provision-local-databases.sh FAKE_DOCKER_COMPOSE_VERSION_STATUS=127

    assert_status 69 "database provisioning without the Compose plugin"
    assert_file_contains "${RUN_OUTPUT}" "docker Compose plugin is required" \
        "database provisioning Compose plugin preflight"
    assert_log_contains "${TEST_ROOT}" $'docker\tcompose\tversion\t--short' \
        "database provisioning Compose plugin preflight command"
    assert_log_excludes "${TEST_ROOT}" $'\tup\t--detach\t--wait' \
        "database provisioning after a missing Compose plugin"
    assert_log_excludes "${TEST_ROOT}" $'\texec\t-T\tpostgres' \
        "database provisioning SQL execution after a missing Compose plugin"
}

test_database_provisioning_requires_a_supported_compose_version() {
    new_harness provision-old-compose provision-local-databases.sh
    add_database_provisioning_sql "${TEST_ROOT}"

    run_target "${TEST_ROOT}" provision-local-databases.sh \
        "FAKE_DOCKER_COMPOSE_VERSION_OUTPUT=2.16.9"

    assert_status 69 "database provisioning with an old Compose plugin"
    assert_file_contains "${RUN_OUTPUT}" "docker Compose 2.17.0 or newer is required" \
        "database provisioning old Compose version"
    assert_log_contains "${TEST_ROOT}" $'docker\tcompose\tversion\t--short' \
        "database provisioning old Compose version probe"
    assert_log_excludes "${TEST_ROOT}" $'\tup\t--detach\t--wait' \
        "database provisioning after an old Compose plugin"
    assert_log_excludes "${TEST_ROOT}" $'\texec\t-T\tpostgres' \
        "database provisioning SQL execution after an old Compose plugin"

    new_harness provision-supported-compose provision-local-databases.sh
    add_database_provisioning_sql "${TEST_ROOT}"

    run_target "${TEST_ROOT}" provision-local-databases.sh \
        "FAKE_DOCKER_COMPOSE_VERSION_OUTPUT=v2.17.0+build.007"

    assert_status 0 "database provisioning with the minimum supported Compose version and build metadata"
    assert_log_contains "${TEST_ROOT}" $'docker\tcompose\tversion\t--short' \
        "database provisioning supported Compose version probe"
    assert_log_contains "${TEST_ROOT}" $'\tup\t--detach\t--wait\t--wait-timeout\t60\tpostgres' \
        "database provisioning supported Compose health wait"

    new_harness provision-ubuntu-compose provision-local-databases.sh
    add_database_provisioning_sql "${TEST_ROOT}"

    run_target "${TEST_ROOT}" provision-local-databases.sh \
        "FAKE_DOCKER_COMPOSE_VERSION_OUTPUT=2.20.2+ds1-0ubuntu1~24.04.1"

    assert_status 0 "database provisioning with a supported Ubuntu Compose package version"
    assert_log_contains "${TEST_ROOT}" $'\tup\t--detach\t--wait\t--wait-timeout\t60\tpostgres' \
        "database provisioning Ubuntu Compose package health wait"

    new_harness provision-desktop-compose provision-local-databases.sh
    add_database_provisioning_sql "${TEST_ROOT}"

    run_target "${TEST_ROOT}" provision-local-databases.sh \
        "FAKE_DOCKER_COMPOSE_VERSION_OUTPUT=2.17.0-desktop.1"

    assert_status 0 "database provisioning with the minimum Docker Desktop Compose version"
    assert_log_contains "${TEST_ROOT}" $'\tup\t--detach\t--wait\t--wait-timeout\t60\tpostgres' \
        "database provisioning Docker Desktop Compose health wait"

    new_harness provision-newer-prerelease-compose provision-local-databases.sh
    add_database_provisioning_sql "${TEST_ROOT}"

    run_target "${TEST_ROOT}" provision-local-databases.sh \
        "FAKE_DOCKER_COMPOSE_VERSION_OUTPUT=v2.17.1-rc.1+build.9"

    assert_status 0 "database provisioning with a numerically newer Compose prerelease"
    assert_log_contains "${TEST_ROOT}" $'\tup\t--detach\t--wait\t--wait-timeout\t60\tpostgres' \
        "database provisioning newer Compose prerelease health wait"

    new_harness provision-threshold-prerelease-compose provision-local-databases.sh
    add_database_provisioning_sql "${TEST_ROOT}"

    run_target "${TEST_ROOT}" provision-local-databases.sh \
        "FAKE_DOCKER_COMPOSE_VERSION_OUTPUT=v2.17.0-rc.1+build.9"

    assert_status 69 "database provisioning with a threshold Compose prerelease"
    assert_file_contains "${RUN_OUTPUT}" "docker Compose 2.17.0 or newer is required" \
        "database provisioning threshold Compose prerelease"
    assert_log_excludes "${TEST_ROOT}" $'\tup\t--detach\t--wait' \
        "database provisioning after a threshold Compose prerelease"
    assert_log_excludes "${TEST_ROOT}" $'\texec\t-T\tpostgres' \
        "database provisioning SQL execution after a threshold Compose prerelease"

    local invalid_semver
    for invalid_semver in 02.17.0 2.017.0 2.17.00 2.17.0-rc.01; do
        new_harness "provision-invalid-compose-${invalid_semver//[^A-Za-z0-9]/-}" \
            provision-local-databases.sh
        add_database_provisioning_sql "${TEST_ROOT}"

        run_target "${TEST_ROOT}" provision-local-databases.sh \
            "FAKE_DOCKER_COMPOSE_VERSION_OUTPUT=${invalid_semver}"

        assert_status 69 "database provisioning with invalid Compose semantic version ${invalid_semver}"
        assert_file_contains "${RUN_OUTPUT}" "must report a semantic version" \
            "database provisioning invalid Compose semantic version ${invalid_semver}"
        assert_log_excludes "${TEST_ROOT}" $'\tup\t--detach\t--wait' \
            "database provisioning after invalid Compose semantic version ${invalid_semver}"
    done

    new_harness provision-malformed-compose provision-local-databases.sh
    add_database_provisioning_sql "${TEST_ROOT}"

    run_target "${TEST_ROOT}" provision-local-databases.sh \
        "FAKE_DOCKER_COMPOSE_VERSION_OUTPUT=not-a-version"

    assert_status 69 "database provisioning with malformed Compose version output"
    assert_file_contains "${RUN_OUTPUT}" "must report a semantic version" \
        "database provisioning malformed Compose version"
    assert_log_contains "${TEST_ROOT}" $'docker\tcompose\tversion\t--short' \
        "database provisioning malformed Compose version probe"
    assert_log_excludes "${TEST_ROOT}" $'\tup\t--detach\t--wait' \
        "database provisioning after malformed Compose version output"
    assert_log_excludes "${TEST_ROOT}" $'\texec\t-T\tpostgres' \
        "database provisioning SQL execution after malformed Compose version output"
}

test_database_provisioning_rejects_unbounded_timeout() {
    new_harness provision-invalid-timeout provision-local-databases.sh
    add_database_provisioning_sql "${TEST_ROOT}"

    run_target "${TEST_ROOT}" provision-local-databases.sh LIFEOS_DATABASE_PROVISION_TIMEOUT_SECONDS=301

    assert_status 64 "database provisioning with an out-of-range timeout"
    assert_file_contains "${RUN_OUTPUT}" "must be between 1 and 300 seconds" "database provisioning timeout validation"
    assert_no_commands_logged "${TEST_ROOT}" \
        "database provisioning with an invalid timeout must not invoke Docker"

    new_harness provision-maximum-timeout provision-local-databases.sh
    add_database_provisioning_sql "${TEST_ROOT}"

    run_target "${TEST_ROOT}" provision-local-databases.sh LIFEOS_DATABASE_PROVISION_TIMEOUT_SECONDS=300

    assert_status 0 "database provisioning with the maximum bounded timeout"
    assert_log_contains "${TEST_ROOT}" \
        $'\tup\t--detach\t--wait\t--wait-timeout\t300\tpostgres' \
        "database provisioning maximum timeout bound"
}

test_database_provisioning_sql_keeps_create_queries_open_for_gexec() {
    local provision_file="${REPOSITORY_ROOT}/infrastructure/docker-compose/provision-databases.sql"

    assert_file_contains "${provision_file}" \
        $'WHERE NOT EXISTS (\n    SELECT 1\n    FROM pg_database\n    WHERE datname = \'lifeos_identity\'\n)\n\\gexec' \
        "identity database provisioning statement"
    assert_file_contains "${provision_file}" \
        $'WHERE NOT EXISTS (\n    SELECT 1\n    FROM pg_database\n    WHERE datname = \'lifeos_task_goal\'\n)\n\\gexec' \
        "task-goal database provisioning statement"
}

test_concurrent_database_provisioning_pins_its_default_image_and_honors_override() {
    local pinned_image="postgres:17-alpine@sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73"
    local override_image="registry.example.test/lifeos-postgres:integration-test"
    local concurrency_script="${REPOSITORY_ROOT}/scripts/test-provision-databases-concurrency.sh"

    new_harness provision-concurrency-default-image test-provision-databases-concurrency.sh
    add_database_provisioning_sql "${TEST_ROOT}"

    # Stop after recording docker run: the remaining test needs a real PostgreSQL server.
    run_target "${TEST_ROOT}" test-provision-databases-concurrency.sh "FAKE_DOCKER_STATUS=75"

    assert_status 75 "concurrent database provisioning with its default image"
    assert_log_contains "${TEST_ROOT}" $'docker\trun\t--detach\t--rm\t--name' \
        "concurrent database provisioning default image invocation"
    assert_log_contains "${TEST_ROOT}" "${pinned_image}" \
        "concurrent database provisioning default image pin"

    new_harness provision-concurrency-override-image test-provision-databases-concurrency.sh
    add_database_provisioning_sql "${TEST_ROOT}"

    run_target "${TEST_ROOT}" test-provision-databases-concurrency.sh \
        "LIFEOS_PROVISION_CONCURRENCY_POSTGRES_IMAGE=${override_image}" \
        "FAKE_DOCKER_STATUS=75"

    assert_status 75 "concurrent database provisioning with an image override"
    assert_log_contains "${TEST_ROOT}" "${override_image}" \
        "concurrent database provisioning image override"
    assert_log_excludes "${TEST_ROOT}" "${pinned_image}" \
        "concurrent database provisioning image override"

    assert_file_contains "${concurrency_script}" \
        'readonly MAXIMUM_OBSERVATION_SECONDS=30' \
        "concurrent database provisioning wall-clock observation bound"
    assert_file_contains "${concurrency_script}" \
        'while (( SECONDS < deadline_seconds )); do' \
        "concurrent database provisioning deadline polling"
    # shellcheck disable=SC2016 # Assert the exact literal wrapper invocation in the target.
    assert_file_contains "${concurrency_script}" \
        '"${OBSERVATION_TIMEOUT_COMMAND}" --signal=KILL "${timeout_seconds}s" docker "$@"' \
        "concurrent database provisioning bounded Docker observation"
    assert_file_contains "${concurrency_script}" \
        "run_docker_with_deadline \"\${diagnostic_deadline_seconds}\" inspect" \
        "concurrent database provisioning bounded failure diagnostics"
    assert_file_contains "${concurrency_script}" \
        "run_docker_with_deadline \"\${cleanup_deadline_seconds}\" rm --force" \
        "concurrent database provisioning bounded container cleanup"
    assert_file_excludes "${concurrency_script}" "MAXIMUM_POLL_ATTEMPTS" \
        "concurrent database provisioning legacy attempt bound"
}

test_concurrent_database_provisioning_requires_a_bounded_observation_timeout() {
    new_harness provision-concurrency-missing-timeout test-provision-databases-concurrency.sh
    add_database_provisioning_sql "${TEST_ROOT}"
    disable_fake_command "${TEST_ROOT}" timeout
    disable_fake_command "${TEST_ROOT}" gtimeout

    local prerequisite
    for prerequisite in bash dirname date od tr awk; do
        add_prerequisite_command "${TEST_ROOT}" "${prerequisite}"
    done

    run_target "${TEST_ROOT}" test-provision-databases-concurrency.sh \
        "PATH=${TEST_ROOT}/bin:${TEST_ROOT}/prerequisite-bin"

    assert_status 69 "concurrent database provisioning without a bounded observation timeout"
    assert_file_contains "${RUN_OUTPUT}" \
        "timeout (or gtimeout on macOS) is required to bound PostgreSQL readiness observations" \
        "concurrent database provisioning timeout prerequisite"
    assert_no_commands_logged "${TEST_ROOT}" \
        "concurrent database provisioning without a timeout must fail before Docker"
}

test_concurrent_database_provisioning_reports_advisory_lock_validation_cleanly() {
    new_harness provision-concurrency-invalid-advisory-lock test-provision-databases-concurrency.sh

    printf '%s\n' \
        'SELECT pg_advisory_xact_lock(1);' \
        '\gexec' \
        '\gexec' \
        > "${TEST_ROOT}/infrastructure/docker-compose/provision-databases.sql"

    run_target "${TEST_ROOT}" test-provision-databases-concurrency.sh

    assert_status 65 "concurrent database provisioning with a transaction-scoped advisory lock"
    assert_file_contains "${RUN_OUTPUT}" \
        'Invalid database provisioning advisory-lock structure: transaction-scoped advisory locks are not valid for separate \gexec transactions (line 1)' \
        "concurrent database provisioning advisory-lock validation diagnostic"
    assert_file_excludes "${RUN_OUTPUT}" '\n' \
        "concurrent database provisioning advisory-lock validation uses real newlines"
    assert_no_commands_logged "${TEST_ROOT}" \
        "concurrent database provisioning advisory-lock validation must fail before Docker"
}

test_verifier_repository_root_resolution_fails_closed() {
    local verifier
    for verifier in verify-pipeline-scripts.sh verify-sbom.sh verify-architecture.sh; do
        new_harness "${verifier%.sh}-root-resolution" "${verifier}"
        ln -s "${TEST_SCRIPT_PATH}" "${TEST_ROOT}/bin/dirname"

        run_target "${TEST_ROOT}" "${verifier}"

        assert_status 1 "${verifier} with an unresolvable repository root"
        assert_log_contains "${TEST_ROOT}" $'dirname\t' \
            "${verifier} repository-root lookup"
        assert_file_contains "${RUN_OUTPUT}" \
            "cd: /definitely-missing-lifeos-script-directory/..: No such file or directory" \
            "${verifier} repository-root resolution failure"
        assert_file_excludes "${RUN_OUTPUT}" "Validated" \
            "${verifier} after repository-root lookup failure"
        assert_file_excludes "${RUN_OUTPUT}" "Architecture boundary verified" \
            "${verifier} after repository-root lookup failure"
    done
}

test_performance_smoke_accepts_100_vus_and_prefers_k6() {
    new_harness performance-k6 performance-smoke-test.sh performance/readiness-smoke.js

    run_target "${TEST_ROOT}" performance-smoke-test.sh \
        "LIFEOS_PERFORMANCE_GATEWAY_MANAGEMENT_BASE_URL=https://gateway.example.test" \
        "LIFEOS_PERFORMANCE_VUS=100" \
        "LIFEOS_PERFORMANCE_DURATION=5s" \
        "LIFEOS_PERFORMANCE_SUMMARY_PATH=build/reports/performance/k6-summary.json"

    assert_status 0 "performance smoke test with 100 VUs"
    assert_log_contains "${TEST_ROOT}" $'k6\trun\t--quiet\t--summary-export\t'"${TEST_ROOT}"$'/build/reports/performance/k6-summary.json' \
        "performance smoke native k6 selection"
    assert_log_excludes "${TEST_ROOT}" $'docker\trun\t' "performance smoke when native k6 is available"
    assert_file_contains "${TEST_ROOT}/scripts/performance/readiness-smoke.js" "checks: ['rate==1']" \
        "performance smoke k6 check-failure threshold"
}

test_performance_smoke_docker_fallback_uses_read_only_repository_mount() {
    new_harness performance-docker performance-smoke-test.sh performance/readiness-smoke.js
    disable_fake_command "${TEST_ROOT}" k6
    local caller_user
    caller_user="$(command -p id -u):$(command -p id -g)"
    local prerequisite
    for prerequisite in bash basename dirname id mkdir mktemp mv readlink rm; do
        add_prerequisite_command "${TEST_ROOT}" "${prerequisite}"
    done

    run_target "${TEST_ROOT}" performance-smoke-test.sh \
        "LIFEOS_OPERATIONAL_TEST_NO_NATIVE_K6=true" \
        "LIFEOS_PERFORMANCE_GATEWAY_MANAGEMENT_BASE_URL=https://gateway.example.test" \
        "LIFEOS_PERFORMANCE_SUMMARY_PATH=build/reports/performance/k6-summary.json"

    assert_status 0 "performance smoke test using Docker fallback"
    assert_log_contains "${TEST_ROOT}" \
        $'docker\trun\t--rm\t--user\t'"${caller_user}"$'\t--volume\t'"${TEST_ROOT}"$':/work:ro\t--volume\t' \
        "performance Docker fallback caller identity and read-only repository mount"
    assert_log_contains "${TEST_ROOT}" \
        $'grafana/k6@sha256:b24f418fc99a26dd57904c952c03bfaf79462be18508acc45aafa07ff68e7df2\trun\t--quiet\t--summary-export\t/tmp/lifeos-k6-summary.' \
        "performance Docker fallback digest-pinned image and temporary summary"
    assert_log_excludes "${TEST_ROOT}" \
        $':/tmp/k6-summary.json' \
        "performance Docker fallback predictable container summary path"
    if [[ ! -s "${TEST_ROOT}/build/reports/performance/k6-summary.json" ]]; then
        fail "performance Docker fallback must write the mounted summary file"
    fi
}

test_performance_smoke_rejects_escaped_summary_paths() {
    new_harness performance-escaped-path performance-smoke-test.sh performance/readiness-smoke.js
    local outside_root
    outside_root="$(mktemp -d "${TMPDIR:-/tmp}/lifeos-operational-outside.XXXXXX")"
    TEST_DIRECTORIES+=("${outside_root}")
    ln -s "${outside_root}" "${TEST_ROOT}/outside"

    run_target "${TEST_ROOT}" performance-smoke-test.sh \
        "LIFEOS_PERFORMANCE_GATEWAY_MANAGEMENT_BASE_URL=https://gateway.example.test" \
        "LIFEOS_PERFORMANCE_SUMMARY_PATH=outside/k6-summary.json"

    assert_status 64 "performance smoke test with a symlink-escaped report path"
    assert_file_contains "${RUN_OUTPUT}" "must stay under the repository root" "performance summary containment"
    assert_log_excludes "${TEST_ROOT}" $'k6\trun\t' "performance smoke after a path escape"
    if [[ -e "${outside_root}/k6-summary.json" ]]; then
        fail "performance smoke test must not write through an escaped summary path"
    fi

    run_target "${TEST_ROOT}" performance-smoke-test.sh \
        "LIFEOS_PERFORMANCE_GATEWAY_MANAGEMENT_BASE_URL=https://gateway.example.test" \
        "LIFEOS_PERFORMANCE_SUMMARY_PATH=${TEST_ROOT}/../$(basename "${outside_root}")/k6-summary.json"

    assert_status 64 "performance smoke test with a lexical escaped report path"
    assert_file_contains "${RUN_OUTPUT}" "must stay under the repository root" "performance lexical summary containment"
    assert_log_excludes "${TEST_ROOT}" $'k6\trun\t' "performance smoke after a lexical path escape"
    if [[ -e "${outside_root}/k6-summary.json" ]]; then
        fail "performance smoke test must not write through a lexically escaped summary path"
    fi
}

test_performance_smoke_bounds_summary_path_input() {
    # Keep the accepted input lexically long while resolving it to the short normal report path.
    # This exercises the boundary without relying on the host filesystem's PATH_MAX.
    local maximum_length=4096
    local summary_suffix="build/reports/performance/k6-summary.json"
    local accepted_summary_path=""
    local padding_length=$((maximum_length - ${#summary_suffix}))

    while (( ${#accepted_summary_path} + 2 <= padding_length )); do
        accepted_summary_path+="./"
    done
    if (( ${#accepted_summary_path} < padding_length )); then
        accepted_summary_path+="/"
    fi
    accepted_summary_path+="${summary_suffix}"
    if (( ${#accepted_summary_path} != maximum_length )); then
        fail "performance summary path boundary fixture must be exactly ${maximum_length} characters"
    fi

    new_harness performance-summary-path-maximum performance-smoke-test.sh performance/readiness-smoke.js
    run_target "${TEST_ROOT}" performance-smoke-test.sh \
        "LIFEOS_PERFORMANCE_GATEWAY_MANAGEMENT_BASE_URL=https://gateway.example.test" \
        "LIFEOS_PERFORMANCE_SUMMARY_PATH=${accepted_summary_path}"

    assert_status 0 "performance smoke test with the maximum summary-path input length"
    if [[ ! -s "${TEST_ROOT}/build/reports/performance/k6-summary.json" ]]; then
        fail "performance smoke test must accept the maximum summary-path input length"
    fi

    new_harness performance-summary-path-over-maximum performance-smoke-test.sh performance/readiness-smoke.js
    run_target "${TEST_ROOT}" performance-smoke-test.sh \
        "LIFEOS_PERFORMANCE_GATEWAY_MANAGEMENT_BASE_URL=https://gateway.example.test" \
        "LIFEOS_PERFORMANCE_SUMMARY_PATH=${accepted_summary_path}x"

    assert_status 64 "performance smoke test with a summary-path input one character over the maximum"
    assert_file_contains "${RUN_OUTPUT}" \
        "LIFEOS_PERFORMANCE_SUMMARY_PATH must not exceed ${maximum_length} characters" \
        "performance summary-path input maximum validation"
    assert_no_commands_logged "${TEST_ROOT}" \
        "performance smoke test must reject an oversized summary-path input before invoking k6 or Docker"
}

test_performance_smoke_rejects_invalid_vus_values() {
    local value
    for value in 0 invalid 101; do
        new_harness "performance-invalid-vus-${value}" performance-smoke-test.sh performance/readiness-smoke.js

        run_target "${TEST_ROOT}" performance-smoke-test.sh \
            "LIFEOS_PERFORMANCE_GATEWAY_MANAGEMENT_BASE_URL=https://gateway.example.test" \
            "LIFEOS_PERFORMANCE_VUS=${value}"

        assert_status 64 "performance smoke test with invalid VUS ${value}"
        assert_file_contains "${RUN_OUTPUT}" "must be between 1 and 100" "performance VUS validation ${value}"
        assert_log_excludes "${TEST_ROOT}" $'k6\trun\t' "performance smoke after invalid VUS ${value}"
    done
}

test_deploy_staging_rejects_unsafe_webhooks_and_uses_bounded_transport() {
    local image_prefix image_tag expected_reference expected_output_reference
    local -a invalid_image_cases=(
        "team//api|build-42|team//api/example-service:build-42"
        "registry.example/lifeos|invalid/tag|registry.example/lifeos/example-service:invalid/tag"
    )
    local invalid_image_case

    new_harness deploy-staging-missing-curl deploy-staging.sh
    add_service_dockerfile "${TEST_ROOT}" example-service
    disable_fake_command "${TEST_ROOT}" curl
    add_prerequisite_command "${TEST_ROOT}" bash
    add_prerequisite_command "${TEST_ROOT}" dirname

    # Supplying only the executable and utility required to start the script makes command -v
    # exercise the production prerequisite check, rather than merely making the fake curl fail
    # after the request has been constructed. This remains portable when /bin links to /usr/bin.
    run_target "${TEST_ROOT}" deploy-staging.sh \
        "PATH=${TEST_ROOT}/bin:${TEST_ROOT}/prerequisite-bin"

    assert_status 69 "staging deployment without curl"
    assert_file_contains "${RUN_OUTPUT}" \
        "curl is required to send the staging deployment request" \
        "staging deployment curl prerequisite"
    assert_log_excludes "${TEST_ROOT}" $'curl\t' "staging deployment without curl"

    new_harness deploy-staging deploy-staging.sh
    add_service_dockerfile "${TEST_ROOT}" example-service
    local sha="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

    run_target "${TEST_ROOT}" deploy-staging.sh \
        "STAGING_DEPLOY_WEBHOOK_URL=http://deploy.example.test" \
        "GITHUB_SHA=${sha}" \
        "GITHUB_REF_NAME=dev" \
        "GITHUB_REPOSITORY=tdespenza/lifeos" \
        "LIFEOS_IMAGE_PREFIX=registry.example/lifeos" \
        "LIFEOS_IMAGE_TAG=build-42"

    assert_status 64 "staging deployment with a non-HTTPS webhook"
    assert_log_excludes "${TEST_ROOT}" $'curl\t' "staging deployment with a non-HTTPS webhook"

    run_target "${TEST_ROOT}" deploy-staging.sh \
        "STAGING_DEPLOY_WEBHOOK_URL=https://deploy.example.test/hooks/staging?signature=example#fragment" \
        "GITHUB_SHA=${sha}" \
        "GITHUB_REF_NAME=dev" \
        "GITHUB_REPOSITORY=tdespenza/lifeos" \
        "LIFEOS_IMAGE_PREFIX=registry.example/lifeos" \
        "LIFEOS_IMAGE_TAG=build-42"

    assert_status 64 "staging deployment with a fragment in the webhook URL"
    assert_file_contains "${RUN_OUTPUT}" \
        "STAGING_DEPLOY_WEBHOOK_URL must use HTTPS" \
        "staging deployment fragment validation"
    assert_log_excludes "${TEST_ROOT}" $'curl\t' \
        "staging deployment with a fragment must not invoke curl"

    for invalid_image_case in "${invalid_image_cases[@]}"; do
        IFS='|' read -r image_prefix image_tag expected_reference <<< "${invalid_image_case}"
        printf -v expected_output_reference '%q' "${expected_reference}"

        run_target "${TEST_ROOT}" deploy-staging.sh \
            "STAGING_DEPLOY_WEBHOOK_URL=https://deploy.example.test/hooks/staging" \
            "GITHUB_SHA=${sha}" \
            "GITHUB_REF_NAME=dev" \
            "GITHUB_REPOSITORY=tdespenza/lifeos" \
            "LIFEOS_IMAGE_PREFIX=${image_prefix}" \
            "LIFEOS_IMAGE_TAG=${image_tag}"

        assert_status 64 "staging deployment with invalid image reference ${expected_reference}"
        assert_file_contains "${RUN_OUTPUT}" \
            "Invalid container image reference ${expected_output_reference}" \
            "staging deployment image-reference validation ${expected_reference}"
        assert_no_commands_logged "${TEST_ROOT}" \
            "staging deployment with invalid image reference ${expected_reference} must not construct a payload or invoke curl"
    done

    run_target "${TEST_ROOT}" deploy-staging.sh \
        "STAGING_DEPLOY_WEBHOOK_URL=https://deploy.example.test/hooks/staging" \
        "GITHUB_SHA=${sha}" \
        "GITHUB_REF_NAME=dev" \
        "GITHUB_REPOSITORY=tdespenza/lifeos" \
        "LIFEOS_IMAGE_PREFIX=registry.example/lifeos" \
        "LIFEOS_IMAGE_TAG=build-42"

    assert_status 0 "staging deployment with a valid webhook"
    assert_log_contains "${TEST_ROOT}" \
        $'curl\t--disable\t--fail\t--silent\t--show-error\t--location\t--max-redirs\t0\t--proto\t=https\t--connect-timeout\t10\t--max-time\t120' \
        "staging deployment bounded HTTPS transport"
    assert_log_entry_excludes "${TEST_ROOT}" \
        "https://deploy.example.test/hooks/staging" \
        $'--retry\t' \
        "staging deployment non-idempotent webhook"
    assert_log_contains "${TEST_ROOT}" \
        $'jq\t-cn\t--arg\trepository\ttdespenza/lifeos\t--arg\tref\tdev\t--arg\tsha\t'"${sha}"$'\t--arg\timagePrefix\tregistry.example/lifeos\t--arg\timageTag\tbuild-42\t--argjson\tservices\t["mock-service"]' \
        "staging deployment payload inputs"
    assert_log_contains "${TEST_ROOT}" \
        $'\t--header\tContent-Type: application/json\t--data\t{"mock":true}\t--output\t/dev/null\thttps://deploy.example.test/hooks/staging' \
        "staging deployment webhook payload"
}

test_service_discovery_requires_its_dependencies() {
    local missing_command image_script
    local sha="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

    for missing_command in find basename sort; do
        for image_script in build-container-images.sh scan-container-images.sh; do
            new_harness "${image_script%.sh}-missing-${missing_command}" "${image_script}"
            add_service_dockerfile "${TEST_ROOT}" example-service
            add_service_discovery_prerequisites_except "${TEST_ROOT}" "${missing_command}"

            run_target "${TEST_ROOT}" "${image_script}" \
                "PATH=${TEST_ROOT}/bin:${TEST_ROOT}/prerequisite-bin"

            assert_status 69 "${image_script} without ${missing_command}"
            assert_file_contains "${RUN_OUTPUT}" \
                "${missing_command} is required to discover service Dockerfiles" \
                "${image_script} ${missing_command} prerequisite"
            assert_no_commands_logged "${TEST_ROOT}" \
                "${image_script} without ${missing_command} must not invoke Docker"
        done

        new_harness "deploy-staging-missing-${missing_command}" deploy-staging.sh
        add_service_dockerfile "${TEST_ROOT}" example-service
        add_service_discovery_prerequisites_except "${TEST_ROOT}" "${missing_command}"

        run_target "${TEST_ROOT}" deploy-staging.sh \
            "PATH=${TEST_ROOT}/bin:${TEST_ROOT}/prerequisite-bin" \
            "STAGING_DEPLOY_WEBHOOK_URL=https://deploy.example.test/hooks/staging" \
            "GITHUB_SHA=${sha}" \
            "GITHUB_REF_NAME=dev" \
            "GITHUB_REPOSITORY=tdespenza/lifeos" \
            "LIFEOS_IMAGE_PREFIX=registry.example/lifeos" \
            "LIFEOS_IMAGE_TAG=build-42"

        assert_status 69 "staging deployment without ${missing_command}"
        assert_file_contains "${RUN_OUTPUT}" \
            "${missing_command} is required to discover service Dockerfiles" \
            "staging deployment ${missing_command} prerequisite"
        assert_no_commands_logged "${TEST_ROOT}" \
            "staging deployment without ${missing_command} must not construct a payload or invoke curl"

        new_harness "staging-smoke-missing-${missing_command}" staging-smoke-test.sh
        add_service_dockerfile "${TEST_ROOT}" example-service
        add_service_discovery_prerequisites_except "${TEST_ROOT}" "${missing_command}" head mktemp rm wc cat

        run_target "${TEST_ROOT}" staging-smoke-test.sh \
            "PATH=${TEST_ROOT}/bin:${TEST_ROOT}/prerequisite-bin" \
            'STAGING_SERVICE_HEALTH_URLS_JSON={"example-service":"https://staging.example.test/actuator/health/readiness"}'

        assert_status 69 "staging smoke test without ${missing_command}"
        assert_file_contains "${RUN_OUTPUT}" \
            "${missing_command} is required to discover service Dockerfiles" \
            "staging smoke ${missing_command} prerequisite"
        assert_log_excludes "${TEST_ROOT}" $'curl\t' \
            "staging smoke test without ${missing_command} must not probe services"
    done
}

test_container_service_discovery_fails_closed_after_partial_output() {
    local image_script

    for image_script in build-container-images.sh scan-container-images.sh; do
        new_harness "${image_script%.sh}-partial-discovery" "${image_script}"
        add_service_dockerfile "${TEST_ROOT}" example-service
        add_service_discovery_prerequisites_except "${TEST_ROOT}" unavailable-command
        add_failing_find_double "${TEST_ROOT}"

        if [[ "${image_script}" == "build-container-images.sh" ]]; then
            # A jar makes the prior fail-open behavior reach docker build, proving discovery
            # failure now stops the build before any Docker action.
            add_service_jar "${TEST_ROOT}" example-service example-service.jar
        fi

        run_target "${TEST_ROOT}" "${image_script}" \
            "PATH=${TEST_ROOT}/bin:${TEST_ROOT}/prerequisite-bin" \
            "LIFEOS_TRIVY_CACHE_DIR=${TEST_ROOT}/trivy-cache" \
            "FAKE_FIND_PARTIAL_OUTPUT=example-service" \
            "FAKE_FIND_STATUS=1"

        assert_status 69 "${image_script} after partial service discovery"
        assert_file_contains "${RUN_OUTPUT}" "Failed to discover service Dockerfiles" \
            "${image_script} partial service-discovery diagnostic"
        assert_log_contains "${TEST_ROOT}" $'find\t' \
            "${image_script} partial service-discovery command"
        assert_no_logged_command "${TEST_ROOT}" docker \
            "${image_script} partial service discovery must not invoke Docker"
    done
}

test_container_service_discovery_preserves_no_dockerfiles_behavior() {
    local image_script

    for image_script in build-container-images.sh scan-container-images.sh; do
        new_harness "${image_script%.sh}-no-services" "${image_script}"

        run_target "${TEST_ROOT}" "${image_script}" \
            "LIFEOS_TRIVY_CACHE_DIR=${TEST_ROOT}/trivy-cache"

        assert_status 66 "${image_script} without Dockerfiles"
        assert_file_contains "${RUN_OUTPUT}" "No service Dockerfiles found in infrastructure/docker" \
            "${image_script} no-Dockerfiles diagnostic"
        assert_no_commands_logged "${TEST_ROOT}" \
            "${image_script} without Dockerfiles must not invoke Docker"
    done
}

test_staging_service_discovery_fails_closed_after_partial_output() {
    local sha="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

    new_harness deploy-staging-partial-discovery deploy-staging.sh
    add_service_dockerfile "${TEST_ROOT}" example-service
    add_service_discovery_prerequisites_except "${TEST_ROOT}" unavailable-command
    add_failing_find_double "${TEST_ROOT}"

    run_target "${TEST_ROOT}" deploy-staging.sh \
        "PATH=${TEST_ROOT}/bin:${TEST_ROOT}/prerequisite-bin" \
        "STAGING_DEPLOY_WEBHOOK_URL=https://deploy.example.test/hooks/staging" \
        "GITHUB_SHA=${sha}" \
        "GITHUB_REF_NAME=dev" \
        "GITHUB_REPOSITORY=tdespenza/lifeos" \
        "LIFEOS_IMAGE_PREFIX=registry.example/lifeos" \
        "LIFEOS_IMAGE_TAG=build-42" \
        "FAKE_FIND_PARTIAL_OUTPUT=example-service" \
        "FAKE_FIND_STATUS=1"

    assert_status 69 "staging deployment after partial service discovery"
    assert_file_contains "${RUN_OUTPUT}" "Failed to discover service Dockerfiles" \
        "staging deployment partial service-discovery diagnostic"
    assert_log_contains "${TEST_ROOT}" $'find\t' \
        "staging deployment partial service-discovery command"
    assert_log_excludes "${TEST_ROOT}" $'jq\t' \
        "staging deployment partial service discovery must not construct a payload"
    assert_log_excludes "${TEST_ROOT}" $'curl\t' \
        "staging deployment partial service discovery must not invoke its webhook"

    new_harness staging-smoke-partial-discovery staging-smoke-test.sh
    add_service_dockerfile "${TEST_ROOT}" example-service
    add_service_discovery_prerequisites_except "${TEST_ROOT}" unavailable-command head mktemp rm wc cat
    add_failing_find_double "${TEST_ROOT}"

    run_target "${TEST_ROOT}" staging-smoke-test.sh \
        "PATH=${TEST_ROOT}/bin:${TEST_ROOT}/prerequisite-bin" \
        'STAGING_SERVICE_HEALTH_URLS_JSON={"example-service":"https://staging.example.test/actuator/health/readiness"}' \
        "FAKE_FIND_PARTIAL_OUTPUT=example-service" \
        "FAKE_FIND_STATUS=1"

    assert_status 69 "staging smoke test after partial service discovery"
    assert_file_contains "${RUN_OUTPUT}" "Failed to discover service Dockerfiles" \
        "staging smoke partial service-discovery diagnostic"
    assert_log_contains "${TEST_ROOT}" $'find\t' \
        "staging smoke partial service-discovery command"
    assert_log_excludes "${TEST_ROOT}" $'jq\t--raw-output\t' \
        "staging smoke partial service discovery must not resolve health URLs"
    assert_log_excludes "${TEST_ROOT}" $'curl\t' \
        "staging smoke partial service discovery must not probe services"
}

test_staging_service_discovery_preserves_no_dockerfiles_behavior() {
    local sha="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

    new_harness deploy-staging-no-services deploy-staging.sh

    run_target "${TEST_ROOT}" deploy-staging.sh \
        "STAGING_DEPLOY_WEBHOOK_URL=https://deploy.example.test/hooks/staging" \
        "GITHUB_SHA=${sha}" \
        "GITHUB_REF_NAME=dev" \
        "GITHUB_REPOSITORY=tdespenza/lifeos" \
        "LIFEOS_IMAGE_PREFIX=registry.example/lifeos" \
        "LIFEOS_IMAGE_TAG=build-42"

    assert_status 66 "staging deployment without Dockerfiles"
    assert_file_contains "${RUN_OUTPUT}" "No service Dockerfiles found in infrastructure/docker" \
        "staging deployment no-Dockerfiles diagnostic"
    assert_log_excludes "${TEST_ROOT}" $'jq\t' \
        "staging deployment without Dockerfiles must not construct a payload"
    assert_log_excludes "${TEST_ROOT}" $'curl\t' \
        "staging deployment without Dockerfiles must not invoke its webhook"

    new_harness staging-smoke-no-services staging-smoke-test.sh

    run_target "${TEST_ROOT}" staging-smoke-test.sh \
        'STAGING_SERVICE_HEALTH_URLS_JSON={"example-service":"https://staging.example.test/actuator/health/readiness"}'

    assert_status 66 "staging smoke test without Dockerfiles"
    assert_file_contains "${RUN_OUTPUT}" "No service Dockerfiles found in infrastructure/docker" \
        "staging smoke no-Dockerfiles diagnostic"
    assert_log_excludes "${TEST_ROOT}" $'jq\t--raw-output\t' \
        "staging smoke without Dockerfiles must not resolve health URLs"
    assert_log_excludes "${TEST_ROOT}" $'curl\t' \
        "staging smoke without Dockerfiles must not probe services"
}

test_staging_scripts_require_dirname_before_resolving_repository_root() {
    local staging_script

    for staging_script in deploy-staging.sh staging-smoke-test.sh; do
        new_harness "${staging_script%.sh}-missing-dirname" "${staging_script}"
        add_service_dockerfile "${TEST_ROOT}" example-service
        add_service_discovery_prerequisites_except "${TEST_ROOT}" dirname cat

        run_target "${TEST_ROOT}" "${staging_script}" \
            "PATH=${TEST_ROOT}/bin:${TEST_ROOT}/prerequisite-bin"

        assert_status 69 "${staging_script} without dirname"
        assert_file_contains "${RUN_OUTPUT}" \
            "dirname is required to resolve the repository root" \
            "${staging_script} dirname prerequisite"
        assert_no_commands_logged "${TEST_ROOT}" \
            "${staging_script} without dirname must not invoke downstream commands"
    done
}

test_container_build_requires_dirname_before_resolving_repository_root() {
    new_harness build-container-images-missing-dirname build-container-images.sh
    add_prerequisite_command "${TEST_ROOT}" bash

    run_target "${TEST_ROOT}" build-container-images.sh \
        "PATH=${TEST_ROOT}/bin:${TEST_ROOT}/prerequisite-bin"

    assert_status 69 "container-image build without dirname"
    assert_file_contains "${RUN_OUTPUT}" \
        "dirname is required to resolve the repository root" \
        "container-image build dirname prerequisite"
    assert_no_commands_logged "${TEST_ROOT}" \
        "container-image build without dirname must not invoke downstream commands"
}

test_security_scan_scripts_require_dirname_before_resolving_repository_root() {
    local security_scan_script

    for security_scan_script in scan-container-images.sh scan-source-security.sh; do
        new_harness "${security_scan_script%.sh}-missing-dirname" "${security_scan_script}"
        add_prerequisite_command "${TEST_ROOT}" bash

        run_target "${TEST_ROOT}" "${security_scan_script}" \
            "PATH=${TEST_ROOT}/bin:${TEST_ROOT}/prerequisite-bin"

        assert_status 69 "${security_scan_script} without dirname"
        assert_file_contains "${RUN_OUTPUT}" \
            "dirname is required to resolve the repository root" \
            "${security_scan_script} dirname prerequisite"
        assert_no_commands_logged "${TEST_ROOT}" \
            "${security_scan_script} without dirname must not invoke downstream commands"
    done
}

test_retry_utilities_are_preflighted_before_operational_paths() {
    local missing_command prerequisite
    for missing_command in head sleep mktemp rm wc; do
        new_harness "staging-smoke-missing-${missing_command}" staging-smoke-test.sh
        if [[ -e "${TEST_ROOT}/bin/${missing_command}" ]]; then
            disable_fake_command "${TEST_ROOT}" "${missing_command}"
        fi
        add_prerequisite_command "${TEST_ROOT}" bash
        add_prerequisite_command "${TEST_ROOT}" dirname
        for prerequisite in head mktemp rm wc; do
            if [[ "${prerequisite}" != "${missing_command}" ]]; then
                add_prerequisite_command "${TEST_ROOT}" "${prerequisite}"
            fi
        done

        run_target "${TEST_ROOT}" staging-smoke-test.sh \
            "PATH=${TEST_ROOT}/bin:${TEST_ROOT}/prerequisite-bin"

        assert_status 69 "staging smoke test without ${missing_command}"
        assert_file_contains "${RUN_OUTPUT}" \
            "curl, head, jq, sleep, mktemp, rm, and wc are required to run the staging smoke test" \
            "staging smoke ${missing_command} prerequisite"
        assert_no_commands_logged "${TEST_ROOT}" \
            "staging smoke test without ${missing_command} must not probe services"
    done

    for missing_command in head sleep mktemp tr rm wc; do
        new_harness "end-to-end-smoke-missing-${missing_command}" end-to-end-smoke-test.sh
        if [[ -e "${TEST_ROOT}/bin/${missing_command}" ]]; then
            disable_fake_command "${TEST_ROOT}" "${missing_command}"
        fi
        add_prerequisite_command "${TEST_ROOT}" bash
        for prerequisite in head mktemp tr rm wc; do
            if [[ "${prerequisite}" != "${missing_command}" ]]; then
                add_prerequisite_command "${TEST_ROOT}" "${prerequisite}"
            fi
        done

        run_target "${TEST_ROOT}" end-to-end-smoke-test.sh \
            "PATH=${TEST_ROOT}/bin:${TEST_ROOT}/prerequisite-bin"

        assert_status 69 "end-to-end smoke test without ${missing_command}"
        assert_file_contains "${RUN_OUTPUT}" \
            "curl, head, jq, rg, sleep, mktemp, tr, rm, and wc are required to run the end-to-end smoke test" \
            "end-to-end ${missing_command} prerequisite"
        assert_no_commands_logged "${TEST_ROOT}" \
            "end-to-end smoke test without ${missing_command} must not make requests"
    done

    new_harness chaos-experiment-missing-date run-chaos-experiment.sh
    add_prerequisite_command "${TEST_ROOT}" bash

    run_target "${TEST_ROOT}" run-chaos-experiment.sh \
        "PATH=${TEST_ROOT}/bin:${TEST_ROOT}/prerequisite-bin"

    assert_status 69 "chaos experiment without date for its local run ID"
    assert_file_contains "${RUN_OUTPUT}" \
        "date is required to generate the local chaos experiment run ID" \
        "chaos experiment date prerequisite"
    assert_no_commands_logged "${TEST_ROOT}" \
        "chaos experiment without date must not make requests"

    for missing_command in head sleep mktemp rm wc; do
        new_harness "chaos-experiment-missing-${missing_command}" run-chaos-experiment.sh
        if [[ -e "${TEST_ROOT}/bin/${missing_command}" ]]; then
            disable_fake_command "${TEST_ROOT}" "${missing_command}"
        fi
        add_prerequisite_command "${TEST_ROOT}" bash
        for prerequisite in head mktemp rm wc; do
            if [[ "${prerequisite}" != "${missing_command}" ]]; then
                add_prerequisite_command "${TEST_ROOT}" "${prerequisite}"
            fi
        done

        run_target "${TEST_ROOT}" run-chaos-experiment.sh \
            "PATH=${TEST_ROOT}/bin:${TEST_ROOT}/prerequisite-bin" \
            "GITHUB_RUN_ID=run-42"

        assert_status 69 "chaos experiment without ${missing_command}"
        assert_file_contains "${RUN_OUTPUT}" \
            "curl, head, jq, sleep, mktemp, rm, and wc are required to run the chaos experiment" \
            "chaos experiment ${missing_command} prerequisite"
        assert_no_commands_logged "${TEST_ROOT}" \
            "chaos experiment without ${missing_command} must not make requests"
    done
}

test_contract_sensitive_posts_reject_redirects() {
    local sha="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

    new_harness deploy-redirect deploy-staging.sh
    add_service_dockerfile "${TEST_ROOT}" example-service

    run_target "${TEST_ROOT}" deploy-staging.sh \
        "STAGING_DEPLOY_WEBHOOK_URL=https://deploy.example.test/hooks/staging" \
        "GITHUB_SHA=${sha}" \
        "GITHUB_REF_NAME=dev" \
        "GITHUB_REPOSITORY=tdespenza/lifeos" \
        "LIFEOS_IMAGE_PREFIX=registry.example/lifeos" \
        "LIFEOS_IMAGE_TAG=build-42" \
        "FAKE_CURL_REDIRECT_URL=https://deploy.example.test/hooks/staging" \
        "FAKE_CURL_REDIRECT_STATUS=302"

    assert_status 47 "staging deployment receiving a 302 redirect"
    assert_log_contains "${TEST_ROOT}" \
        $'curl\t--disable\t--fail\t--silent\t--show-error\t--location\t--max-redirs\t0\t--proto\t=https' \
        "staging deployment redirect rejection"
    assert_file_excludes "${RUN_OUTPUT}" \
        "Staging deployment endpoint accepted" \
        "staging deployment after a redirect"

    new_harness end-to-end-post-redirect end-to-end-smoke-test.sh

    # The fake response carries the canonical ID on a 302 hop and a different ID on the synthetic
    # final response. Without --max-redirs 0, the old request would follow the redirect and the
    # broad header scan would incorrectly accept that intermediate correlation header.
    run_target "${TEST_ROOT}" end-to-end-smoke-test.sh \
        "LIFEOS_E2E_GATEWAY_BASE_URL=https://gateway.example.test" \
        "LIFEOS_E2E_GATEWAY_MANAGEMENT_BASE_URL=https://gateway-management.example.test" \
        "LIFEOS_E2E_IDENTITY_MANAGEMENT_BASE_URL=https://identity-management.example.test" \
        "FAKE_CURL_REDIRECT_URL=https://gateway.example.test/api/v1/accounts" \
        "FAKE_CURL_REDIRECT_STATUS=302" \
        "FAKE_CURL_REDIRECT_INTERMEDIATE_CORRELATION_ID=11111111-1111-4111-8111-111111111111" \
        "FAKE_CURL_REDIRECT_FINAL_CORRELATION_ID=different-final-correlation-id"

    assert_status 47 "end-to-end account request receiving a 302 redirect"
    assert_log_contains "${TEST_ROOT}" \
        $'curl\t--disable\t--silent\t--show-error\t--location\t--max-redirs\t0\t--proto\t=https\t--connect-timeout\t10\t--max-time\t20\t--header\tX-Correlation-ID: 11111111-1111-4111-8111-111111111111' \
        "end-to-end account redirect rejection"
    assert_file_excludes "${RUN_OUTPUT}" \
        "End-to-end gateway-to-identity contract passed" \
        "end-to-end smoke after an intermediate correlation header"
    assert_log_contains "${TEST_ROOT}" \
        $'curl\t--disable\t--fail\t--silent\t--show-error\t--location\t--max-redirs\t0\t--proto\t=https\t--connect-timeout\t10\t--max-time\t20\thttps://gateway-management.example.test/actuator/health/readiness' \
        "end-to-end readiness redirect rejection"

    new_harness chaos-redirect run-chaos-experiment.sh

    run_target "${TEST_ROOT}" run-chaos-experiment.sh \
        "LIFEOS_CHAOS_EXPERIMENT_WEBHOOK_URL=https://chaos.example.test/experiments" \
        "LIFEOS_CHAOS_GATEWAY_HEALTH_URL=https://gateway-management.example.test/actuator/health/readiness" \
        "LIFEOS_CHAOS_IDENTITY_HEALTH_URL=https://identity-management.example.test/actuator/health/readiness" \
        "LIFEOS_CHAOS_TASK_GOAL_HEALTH_URL=https://task-goal.example.test/actuator/health" \
        "FAKE_CURL_REDIRECT_URL=https://chaos.example.test/experiments" \
        "FAKE_CURL_REDIRECT_STATUS=303"

    assert_status 47 "chaos webhook receiving a 303 redirect"
    assert_log_contains "${TEST_ROOT}" \
        $'curl\t--disable\t--fail\t--silent\t--show-error\t--location\t--max-redirs\t0\t--proto\t=https\t--connect-timeout\t10\t--max-time\t300' \
        "chaos webhook redirect rejection"
    assert_log_line_count "${TEST_ROOT}" $'curl\t' 1 \
        "chaos recovery probes after a redirected webhook"
}

test_staging_and_end_to_end_smoke_fail_closed_before_live_traffic() {
    new_harness staging-missing-config staging-smoke-test.sh
    add_service_dockerfile "${TEST_ROOT}" example-service

    run_target "${TEST_ROOT}" staging-smoke-test.sh

    assert_status 64 "staging smoke test without service URLs"
    assert_file_contains "${RUN_OUTPUT}" "STAGING_SERVICE_HEALTH_URLS_JSON is required" "staging smoke configuration validation"
    assert_log_excludes "${TEST_ROOT}" $'curl\t' "staging smoke without service URLs"

    new_harness staging-malformed-config staging-smoke-test.sh
    add_service_dockerfile "${TEST_ROOT}" example-service

    run_target "${TEST_ROOT}" staging-smoke-test.sh \
        'STAGING_SERVICE_HEALTH_URLS_JSON={"example-service":'

    assert_status 64 "staging smoke test with malformed service URL JSON"
    assert_file_contains "${RUN_OUTPUT}" \
        "STAGING_SERVICE_HEALTH_URLS_JSON must map service names to HTTPS actuator health URLs" \
        "staging smoke malformed JSON validation"
    assert_log_excludes "${TEST_ROOT}" $'curl\t' "staging smoke with malformed service URL JSON"

    new_harness staging-structurally-invalid-config staging-smoke-test.sh
    add_service_dockerfile "${TEST_ROOT}" example-service

    run_target "${TEST_ROOT}" staging-smoke-test.sh \
        'STAGING_SERVICE_HEALTH_URLS_JSON=["https://staging.example.test/actuator/health/readiness"]'

    assert_status 64 "staging smoke test with a non-object service URL map"
    assert_file_contains "${RUN_OUTPUT}" \
        "STAGING_SERVICE_HEALTH_URLS_JSON must map service names to HTTPS actuator health URLs" \
        "staging smoke structural JSON validation"
    assert_log_excludes "${TEST_ROOT}" $'curl\t' "staging smoke with a non-object service URL map"

}

test_end_to_end_smoke_accepts_correlation_headers_without_optional_whitespace() {
    local separator

    for separator in none htab; do
        new_harness "end-to-end-correlation-header-${separator}" end-to-end-smoke-test.sh

        run_target "${TEST_ROOT}" end-to-end-smoke-test.sh \
            "LIFEOS_E2E_GATEWAY_BASE_URL=https://gateway.example.test" \
            "LIFEOS_E2E_GATEWAY_MANAGEMENT_BASE_URL=https://gateway-management.example.test" \
            "LIFEOS_E2E_IDENTITY_MANAGEMENT_BASE_URL=https://identity-management.example.test" \
            "FAKE_CURL_ACCOUNT_REGISTRATION_STATUS_CODE=400" \
            "FAKE_CURL_ACCOUNT_REGISTRATION_CORRELATION_SEPARATOR=${separator}"

        assert_status 0 "end-to-end smoke with a ${separator} correlation-header separator"
        assert_file_contains "${RUN_OUTPUT}" \
            "End-to-end gateway-to-identity contract passed" \
            "end-to-end smoke with a ${separator} correlation-header separator"
    done

    new_harness end-to-end-correlation-header-mismatch end-to-end-smoke-test.sh

    run_target "${TEST_ROOT}" end-to-end-smoke-test.sh \
        "LIFEOS_E2E_GATEWAY_BASE_URL=https://gateway.example.test" \
        "LIFEOS_E2E_GATEWAY_MANAGEMENT_BASE_URL=https://gateway-management.example.test" \
        "LIFEOS_E2E_IDENTITY_MANAGEMENT_BASE_URL=https://identity-management.example.test" \
        "FAKE_CURL_ACCOUNT_REGISTRATION_STATUS_CODE=400" \
        "FAKE_CURL_ACCOUNT_REGISTRATION_CORRELATION_ID=not-the-canonical-correlation-id"

    assert_status 65 "end-to-end smoke with a mismatched correlation header"
    assert_file_contains "${RUN_OUTPUT}" \
        "Gateway-to-identity flow did not preserve the canonical correlation ID" \
        "end-to-end smoke with a mismatched correlation header"
}

test_end_to_end_smoke_reports_header_buffer_allocation_failures() {
    new_harness end-to-end-header-buffer-allocation-failure end-to-end-smoke-test.sh
    add_mktemp_double "${TEST_ROOT}"
    local mktemp_call_count_file="${TEST_ROOT}/mktemp-call-count"
    local temporary_file_directory="${TEST_ROOT}/temporary-files"
    mkdir -p "${temporary_file_directory}"

    # The first two allocations are the gateway and identity readiness buffers. Failing exactly
    # the third allocation therefore exercises only the response-header buffer for registration.
    run_target "${TEST_ROOT}" end-to-end-smoke-test.sh \
        "LIFEOS_E2E_GATEWAY_BASE_URL=https://gateway.example.test" \
        "LIFEOS_E2E_GATEWAY_MANAGEMENT_BASE_URL=https://gateway-management.example.test" \
        "LIFEOS_E2E_IDENTITY_MANAGEMENT_BASE_URL=https://identity-management.example.test" \
        "TMPDIR=${temporary_file_directory}" \
        "FAKE_MKTEMP_CALL_COUNT_FILE=${mktemp_call_count_file}" \
        "FAKE_MKTEMP_FAIL_ON_CALL=3"

    assert_status 1 "end-to-end smoke with an unallocatable response-header buffer"
    assert_file_contains "${RUN_OUTPUT}" \
        "Unable to allocate a temporary response-header buffer for the gateway-to-identity contract" \
        "end-to-end response-header allocation diagnostic"
    assert_file_contains "${mktemp_call_count_file}" "3" \
        "end-to-end response-header allocation call count"
    assert_log_line_count "${TEST_ROOT}" "mktemp" 3 \
        "end-to-end response-header allocation count"
    assert_health_probe_count "${TEST_ROOT}" 2 \
        "end-to-end response-header allocation prerequisites"
    assert_log_excludes "${TEST_ROOT}" "/api/v1/accounts" \
        "end-to-end response-header allocation must not invoke registration curl"
    assert_directory_empty "${temporary_file_directory}" \
        "end-to-end response-header allocation must clean readiness buffers"
}

test_operational_scripts_share_https_authority_and_health_helpers() {
    local shared_validation_script="${REPOSITORY_ROOT}/scripts/https-authority-validation.sh"
    local shared_source_invocation="source \"\${HTTPS_AUTHORITY_VALIDATION_SCRIPT}\""
    local script

    assert_readable_file "${shared_validation_script}" "shared HTTPS authority validation library"
    assert_file_contains "${shared_validation_script}" "has_valid_https_authority()" \
        "shared HTTPS authority validation library authority function"
    assert_file_contains "${shared_validation_script}" "is_legacy_ipv4_component_sequence()" \
        "shared HTTPS authority validation library legacy IPv4 function"
    assert_file_contains "${shared_validation_script}" "health_check_delay_seconds()" \
        "shared health-check retry helper"
    assert_file_contains "${shared_validation_script}" "wait_for_health()" \
        "shared health-check probe"
    assert_file_contains "${shared_validation_script}" "readonly HEALTH_CHECK_MAX_ATTEMPTS=6" \
        "shared health-check attempt limit"
    assert_file_contains "${shared_validation_script}" "readonly HEALTH_RESPONSE_MAX_BYTES=65536" \
        "shared health-response byte limit"
    assert_file_contains "${shared_validation_script}" "readonly HEALTH_CHECK_MAX_BACKOFF_SECONDS=16" \
        "shared health-check retry backoff cap"

    for script in staging-smoke-test.sh end-to-end-smoke-test.sh run-chaos-experiment.sh; do
        assert_file_contains "${REPOSITORY_ROOT}/scripts/${script}" \
            "${shared_source_invocation}" \
            "${script} shared HTTPS authority validation source"
        assert_file_excludes "${REPOSITORY_ROOT}/scripts/${script}" "is_valid_ipv4_literal()" \
            "${script} must not duplicate the shared IPv4 validator"
        assert_file_excludes "${REPOSITORY_ROOT}/scripts/${script}" "has_valid_https_authority()" \
            "${script} must not duplicate the shared HTTPS authority validator"
        assert_file_excludes "${REPOSITORY_ROOT}/scripts/${script}" "health_check_delay_seconds()" \
            "${script} must not duplicate the shared health-check retry helper"
        assert_file_excludes "${REPOSITORY_ROOT}/scripts/${script}" "HEALTH_CHECK_MAX_BACKOFF_SECONDS" \
            "${script} must not duplicate the shared health-check retry backoff cap"
        assert_file_excludes "${REPOSITORY_ROOT}/scripts/${script}" "HEALTH_CHECK_MAX_ATTEMPTS=6" \
            "${script} must not duplicate the shared health-check attempt limit"
        assert_file_excludes "${REPOSITORY_ROOT}/scripts/${script}" "HEALTH_RESPONSE_MAX_BYTES=65536" \
            "${script} must not duplicate the shared health-response byte limit"
        assert_file_excludes "${REPOSITORY_ROOT}/scripts/${script}" "wait_for_health()" \
            "${script} must not duplicate the shared health-check probe"
    done

    new_harness shared-https-authority-validation end-to-end-smoke-test.sh
    assert_readable_file "${TEST_ROOT}/scripts/https-authority-validation.sh" \
        "operational harness shared HTTPS authority validation library"

    if ! bash -c 'set -euo pipefail; source "$1"; first_attempts="${HEALTH_CHECK_MAX_ATTEMPTS}"; source "$1"; [[ "${HEALTH_CHECK_MAX_ATTEMPTS}" == "${first_attempts}" && "${HEALTH_RESPONSE_MAX_BYTES}" == 65536 ]]' \
        _ "${TEST_ROOT}/scripts/https-authority-validation.sh"; then
        fail "shared HTTPS authority validation library must be safely sourceable twice"
    fi
}

test_operational_urls_reject_userinfo_before_live_traffic() {
    local sha="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    local invalid_setting
    local case_number=0

    new_harness staging-userinfo staging-smoke-test.sh
    add_service_dockerfile "${TEST_ROOT}" example-service

    run_target "${TEST_ROOT}" staging-smoke-test.sh \
        'STAGING_SERVICE_HEALTH_URLS_JSON={"example-service":"https://user:password@staging.example.test/actuator/health/readiness"}' \
        "FAKE_JQ_SERVICE_URL=https://user:password@staging.example.test/actuator/health/readiness"

    assert_status 64 "staging smoke test with userinfo in a health URL"
    assert_file_contains "${RUN_OUTPUT}" \
        "Staging health URL for example-service must be a canonical HTTPS actuator health endpoint" \
        "staging smoke userinfo validation"
    assert_log_excludes "${TEST_ROOT}" $'curl\t' \
        "staging smoke with userinfo must not probe services"

    for invalid_setting in \
        "LIFEOS_E2E_GATEWAY_BASE_URL=https://user:password@gateway.example.test" \
        "LIFEOS_E2E_GATEWAY_MANAGEMENT_BASE_URL=https://user:password@gateway-management.example.test" \
        "LIFEOS_E2E_IDENTITY_MANAGEMENT_BASE_URL=https://user:password@identity-management.example.test"; do
        ((case_number += 1))
        new_harness "end-to-end-userinfo-${case_number}" end-to-end-smoke-test.sh

        run_target "${TEST_ROOT}" end-to-end-smoke-test.sh \
            "LIFEOS_E2E_GATEWAY_BASE_URL=https://gateway.example.test" \
            "LIFEOS_E2E_GATEWAY_MANAGEMENT_BASE_URL=https://gateway-management.example.test" \
            "LIFEOS_E2E_IDENTITY_MANAGEMENT_BASE_URL=https://identity-management.example.test" \
            "${invalid_setting}"

        assert_status 64 "end-to-end smoke test with userinfo in endpoint ${case_number}"
        assert_no_commands_logged "${TEST_ROOT}" \
            "end-to-end smoke test with userinfo must not invoke dependencies"
    done

    new_harness performance-userinfo performance-smoke-test.sh performance/readiness-smoke.js

    run_target "${TEST_ROOT}" performance-smoke-test.sh \
        "LIFEOS_PERFORMANCE_GATEWAY_MANAGEMENT_BASE_URL=https://user:password@gateway.example.test"

    assert_status 64 "performance smoke test with userinfo in the target URL"
    assert_file_contains "${RUN_OUTPUT}" \
        "LIFEOS_PERFORMANCE_GATEWAY_MANAGEMENT_BASE_URL must be a canonical HTTPS URL" \
        "performance smoke userinfo validation"
    assert_no_commands_logged "${TEST_ROOT}" \
        "performance smoke test with userinfo must not run k6 or Docker"

    new_harness deploy-userinfo deploy-staging.sh

    run_target "${TEST_ROOT}" deploy-staging.sh \
        "STAGING_DEPLOY_WEBHOOK_URL=https://user:password@deploy.example.test/hooks/staging" \
        "GITHUB_SHA=${sha}" \
        "GITHUB_REF_NAME=dev" \
        "GITHUB_REPOSITORY=tdespenza/lifeos" \
        "LIFEOS_IMAGE_PREFIX=registry.example/lifeos" \
        "LIFEOS_IMAGE_TAG=build-42"

    assert_status 64 "staging deployment with userinfo in the webhook URL"
    assert_file_contains "${RUN_OUTPUT}" \
        "STAGING_DEPLOY_WEBHOOK_URL must use HTTPS" \
        "staging deployment userinfo validation"
    assert_no_commands_logged "${TEST_ROOT}" \
        "staging deployment with userinfo must not construct a payload or invoke curl"

    new_harness deploy-query-at-sign deploy-staging.sh
    add_service_dockerfile "${TEST_ROOT}" example-service

    run_target "${TEST_ROOT}" deploy-staging.sh \
        "STAGING_DEPLOY_WEBHOOK_URL=https://deploy.example.test/hooks/staging?signature=service@example.test" \
        "GITHUB_SHA=${sha}" \
        "GITHUB_REF_NAME=dev" \
        "GITHUB_REPOSITORY=tdespenza/lifeos" \
        "LIFEOS_IMAGE_PREFIX=registry.example/lifeos" \
        "LIFEOS_IMAGE_TAG=build-42"

    assert_status 0 "staging deployment with an at sign in a webhook query"
    assert_log_contains "${TEST_ROOT}" \
        "https://deploy.example.test/hooks/staging?signature=service@example.test" \
        "staging deployment preserves signed webhook queries"
}

test_operational_urls_reject_malformed_authorities_before_live_traffic() {
    local invalid_health_url
    local case_number=0

    # Staging resolves each health URL from JSON, so exercise a missing host, an empty host before
    # a port, and malformed/out-of-range ports before the first health curl can be reached.
    for invalid_health_url in \
        "https:///actuator/health/readiness" \
        "https://:443/actuator/health/readiness" \
        "https://staging.example.test:not-a-port/actuator/health/readiness" \
        "https://staging.example.test:/actuator/health/readiness" \
        "https://staging.example.test:0/actuator/health/readiness" \
        "https://staging.example.test:65536/actuator/health/readiness" \
        "https://exa mple/actuator/health/readiness" \
        "https://[::::]/actuator/health/readiness" \
        "https://2130706433/actuator/health/readiness" \
        "https://0x7f000001/actuator/health/readiness" \
        "https://0x7f.0.0.1/actuator/health/readiness" \
        "https://4294967296/actuator/health/readiness" \
        "https://9999999999/actuator/health/readiness" \
        "https://0x100000000/actuator/health/readiness" \
        "https://040000000001/actuator/health/readiness" \
        "https://[192.0.2.1::1]/actuator/health/readiness"; do
        ((case_number += 1))
        new_harness "staging-malformed-authority-${case_number}" staging-smoke-test.sh
        add_service_dockerfile "${TEST_ROOT}" example-service

        run_target "${TEST_ROOT}" staging-smoke-test.sh \
            "STAGING_SERVICE_HEALTH_URLS_JSON={\"example-service\":\"${invalid_health_url}\"}" \
            "FAKE_JQ_SERVICE_URL=${invalid_health_url}"

        assert_status 64 "staging smoke test with malformed authority ${case_number}"
        assert_log_excludes "${TEST_ROOT}" $'curl\t' \
            "staging smoke test with malformed authority ${case_number} must not probe services"
    done

    # Each end-to-end base URL passes through the same authority validation before readiness or
    # registration traffic. Use a distinct malformed authority for every input boundary.
    case_number=0
    local invalid_setting
    for invalid_setting in \
        "LIFEOS_E2E_GATEWAY_BASE_URL=https://gateway.example.test:not-a-port" \
        "LIFEOS_E2E_GATEWAY_MANAGEMENT_BASE_URL=https://gateway-management.example.test:" \
        "LIFEOS_E2E_IDENTITY_MANAGEMENT_BASE_URL=https://:443" \
        "LIFEOS_E2E_GATEWAY_BASE_URL=https://exa mple" \
        "LIFEOS_E2E_GATEWAY_MANAGEMENT_BASE_URL=https://[::::]" \
        "LIFEOS_E2E_GATEWAY_BASE_URL=https://2130706433" \
        "LIFEOS_E2E_GATEWAY_MANAGEMENT_BASE_URL=https://0x7f000001" \
        "LIFEOS_E2E_IDENTITY_MANAGEMENT_BASE_URL=https://0x7f.0.0.1" \
        "LIFEOS_E2E_GATEWAY_BASE_URL=https://4294967296" \
        "LIFEOS_E2E_GATEWAY_MANAGEMENT_BASE_URL=https://9999999999" \
        "LIFEOS_E2E_IDENTITY_MANAGEMENT_BASE_URL=https://0x100000000" \
        "LIFEOS_E2E_GATEWAY_BASE_URL=https://040000000001" \
        "LIFEOS_E2E_GATEWAY_BASE_URL=https://[192.0.2.1::1]"; do
        ((case_number += 1))
        new_harness "end-to-end-malformed-authority-${case_number}" end-to-end-smoke-test.sh

        run_target "${TEST_ROOT}" end-to-end-smoke-test.sh \
            "LIFEOS_E2E_GATEWAY_BASE_URL=https://gateway.example.test" \
            "LIFEOS_E2E_GATEWAY_MANAGEMENT_BASE_URL=https://gateway-management.example.test" \
            "LIFEOS_E2E_IDENTITY_MANAGEMENT_BASE_URL=https://identity-management.example.test" \
            "${invalid_setting}"

        assert_status 64 "end-to-end smoke test with malformed authority ${case_number}"
        assert_no_commands_logged "${TEST_ROOT}" \
            "end-to-end smoke test with malformed authority ${case_number} must not invoke dependencies"
    done

    # Validate every chaos input before payload construction: the webhook, both readiness URLs,
    # and the Task/Goal health URL must all fail closed rather than starting curl retries.
    case_number=0
    for invalid_setting in \
        "LIFEOS_CHAOS_EXPERIMENT_WEBHOOK_URL=https://chaos.example.test:not-a-port/experiments" \
        "LIFEOS_CHAOS_GATEWAY_HEALTH_URL=https://gateway-management.example.test:/actuator/health/readiness" \
        "LIFEOS_CHAOS_IDENTITY_HEALTH_URL=https://:443/actuator/health/readiness" \
        "LIFEOS_CHAOS_TASK_GOAL_HEALTH_URL=https://task-goal.example.test:65536/actuator/health" \
        "LIFEOS_CHAOS_EXPERIMENT_WEBHOOK_URL=https://exa mple/experiments" \
        "LIFEOS_CHAOS_GATEWAY_HEALTH_URL=https://[::::]/actuator/health/readiness" \
        "LIFEOS_CHAOS_EXPERIMENT_WEBHOOK_URL=https://2130706433/experiments" \
        "LIFEOS_CHAOS_GATEWAY_HEALTH_URL=https://0x7f000001/actuator/health/readiness" \
        "LIFEOS_CHAOS_IDENTITY_HEALTH_URL=https://0x7f.0.0.1/actuator/health/readiness" \
        "LIFEOS_CHAOS_TASK_GOAL_HEALTH_URL=https://4294967296/actuator/health" \
        "LIFEOS_CHAOS_EXPERIMENT_WEBHOOK_URL=https://9999999999/experiments" \
        "LIFEOS_CHAOS_GATEWAY_HEALTH_URL=https://0x100000000/actuator/health/readiness" \
        "LIFEOS_CHAOS_IDENTITY_HEALTH_URL=https://040000000001/actuator/health/readiness" \
        "LIFEOS_CHAOS_TASK_GOAL_HEALTH_URL=https://[192.0.2.1::1]/actuator/health"; do
        ((case_number += 1))
        new_harness "chaos-malformed-authority-${case_number}" run-chaos-experiment.sh

        run_target "${TEST_ROOT}" run-chaos-experiment.sh \
            "LIFEOS_CHAOS_EXPERIMENT_WEBHOOK_URL=https://chaos.example.test/experiments" \
            "LIFEOS_CHAOS_GATEWAY_HEALTH_URL=https://gateway-management.example.test/actuator/health/readiness" \
            "LIFEOS_CHAOS_IDENTITY_HEALTH_URL=https://identity-management.example.test/actuator/health/readiness" \
            "LIFEOS_CHAOS_TASK_GOAL_HEALTH_URL=https://task-goal.example.test/actuator/health" \
            "GITHUB_RUN_ID=run-42" \
            "${invalid_setting}"

        assert_status 64 "chaos experiment with malformed authority ${case_number}"
        assert_no_commands_logged "${TEST_ROOT}" \
            "chaos experiment with malformed authority ${case_number} must not invoke dependencies"
    done
}

test_operational_urls_accept_explicit_valid_ports_and_paths() {
    new_harness staging-explicit-port staging-smoke-test.sh
    add_service_dockerfile "${TEST_ROOT}" example-service

    run_target "${TEST_ROOT}" staging-smoke-test.sh \
        'STAGING_SERVICE_HEALTH_URLS_JSON={"example-service":"https://staging.example.test:8443/private@management/actuator/health/readiness"}' \
        "FAKE_JQ_SERVICE_URL=https://staging.example.test:8443/private@management/actuator/health/readiness"

    assert_status 0 "staging smoke test with an explicit valid port and management path"
    assert_log_contains "${TEST_ROOT}" \
        "https://staging.example.test:8443/private@management/actuator/health/readiness" \
        "staging smoke test preserves an explicit valid port and path"

    new_harness end-to-end-explicit-port end-to-end-smoke-test.sh

    run_target "${TEST_ROOT}" end-to-end-smoke-test.sh \
        "LIFEOS_E2E_GATEWAY_BASE_URL=https://gateway.example.test:8443/public@edge" \
        "LIFEOS_E2E_GATEWAY_MANAGEMENT_BASE_URL=https://gateway-management.example.test:8444/private@management" \
        "LIFEOS_E2E_IDENTITY_MANAGEMENT_BASE_URL=https://identity-management.example.test:8445/private@management" \
        "FAKE_CURL_ACCOUNT_REGISTRATION_STATUS_CODE=400"

    assert_status 0 "end-to-end smoke test with explicit valid ports and paths"
    assert_log_contains "${TEST_ROOT}" \
        "https://gateway.example.test:8443/public@edge/api/v1/accounts" \
        "end-to-end smoke test preserves an explicit gateway port and path"

    new_harness chaos-explicit-port run-chaos-experiment.sh

    run_target "${TEST_ROOT}" run-chaos-experiment.sh \
        "LIFEOS_CHAOS_EXPERIMENT_WEBHOOK_URL=https://chaos.example.test:8443/experiments@v1" \
        "LIFEOS_CHAOS_GATEWAY_HEALTH_URL=https://gateway-management.example.test:8444/private@management/actuator/health/readiness" \
        "LIFEOS_CHAOS_IDENTITY_HEALTH_URL=https://identity-management.example.test:8445/private@management/actuator/health/readiness" \
        "LIFEOS_CHAOS_TASK_GOAL_HEALTH_URL=https://task-goal.example.test:8446/private@management/actuator/health" \
        "GITHUB_RUN_ID=run-42"

    assert_status 0 "chaos experiment with explicit valid ports and paths"
    assert_log_contains "${TEST_ROOT}" \
        "https://chaos.example.test:8443/experiments@v1" \
        "chaos experiment preserves an explicit webhook port and path"
}

test_operational_urls_accept_valid_bracketed_ipv6_authorities() {
    new_harness staging-ipv6-authority staging-smoke-test.sh
    add_service_dockerfile "${TEST_ROOT}" example-service

    run_target "${TEST_ROOT}" staging-smoke-test.sh \
        'STAGING_SERVICE_HEALTH_URLS_JSON={"example-service":"https://[2001:db8::1]:8443/private/actuator/health/readiness"}' \
        "FAKE_JQ_SERVICE_URL=https://[2001:db8::1]:8443/private/actuator/health/readiness"

    assert_status 0 "staging smoke test with a valid bracketed IPv6 authority"
    assert_log_contains "${TEST_ROOT}" \
        "https://[2001:db8::1]:8443/private/actuator/health/readiness" \
        "staging smoke test preserves a valid bracketed IPv6 authority"

    new_harness end-to-end-ipv6-authority end-to-end-smoke-test.sh

    run_target "${TEST_ROOT}" end-to-end-smoke-test.sh \
        "LIFEOS_E2E_GATEWAY_BASE_URL=https://[2001:db8::1]:8443/public" \
        "LIFEOS_E2E_GATEWAY_MANAGEMENT_BASE_URL=https://[2001:db8::2]:8444/management" \
        "LIFEOS_E2E_IDENTITY_MANAGEMENT_BASE_URL=https://[2001:db8::3]:8445/management" \
        "FAKE_CURL_ACCOUNT_REGISTRATION_STATUS_CODE=400"

    assert_status 0 "end-to-end smoke test with valid bracketed IPv6 authorities"
    assert_log_contains "${TEST_ROOT}" \
        "https://[2001:db8::1]:8443/public/api/v1/accounts" \
        "end-to-end smoke test preserves a valid bracketed IPv6 gateway authority"

    new_harness chaos-ipv6-authority run-chaos-experiment.sh

    run_target "${TEST_ROOT}" run-chaos-experiment.sh \
        "LIFEOS_CHAOS_EXPERIMENT_WEBHOOK_URL=https://[2001:db8::1]:8443/experiments" \
        "LIFEOS_CHAOS_GATEWAY_HEALTH_URL=https://[2001:db8::2]:8444/management/actuator/health/readiness" \
        "LIFEOS_CHAOS_IDENTITY_HEALTH_URL=https://[2001:db8::3]:8445/management/actuator/health/readiness" \
        "LIFEOS_CHAOS_TASK_GOAL_HEALTH_URL=https://[2001:db8::4]:8446/management/actuator/health" \
        "GITHUB_RUN_ID=run-42"

    assert_status 0 "chaos experiment with valid bracketed IPv6 authorities"
    assert_log_contains "${TEST_ROOT}" \
        "https://[2001:db8::1]:8443/experiments" \
        "chaos experiment preserves a valid bracketed IPv6 webhook authority"
}

test_operational_urls_accept_canonical_dotted_ipv4_authorities() {
    new_harness staging-ipv4-authority staging-smoke-test.sh
    add_service_dockerfile "${TEST_ROOT}" example-service

    run_target "${TEST_ROOT}" staging-smoke-test.sh \
        'STAGING_SERVICE_HEALTH_URLS_JSON={"example-service":"https://192.0.2.1:8443/private/actuator/health/readiness"}' \
        "FAKE_JQ_SERVICE_URL=https://192.0.2.1:8443/private/actuator/health/readiness"

    assert_status 0 "staging smoke test with a canonical dotted IPv4 authority"
    assert_log_contains "${TEST_ROOT}" \
        "https://192.0.2.1:8443/private/actuator/health/readiness" \
        "staging smoke test preserves a canonical dotted IPv4 authority"

    new_harness end-to-end-ipv4-authority end-to-end-smoke-test.sh

    run_target "${TEST_ROOT}" end-to-end-smoke-test.sh \
        "LIFEOS_E2E_GATEWAY_BASE_URL=https://198.51.100.1:8443/public" \
        "LIFEOS_E2E_GATEWAY_MANAGEMENT_BASE_URL=https://198.51.100.2:8444/management" \
        "LIFEOS_E2E_IDENTITY_MANAGEMENT_BASE_URL=https://198.51.100.3:8445/management" \
        "FAKE_CURL_ACCOUNT_REGISTRATION_STATUS_CODE=400"

    assert_status 0 "end-to-end smoke test with canonical dotted IPv4 authorities"
    assert_log_contains "${TEST_ROOT}" \
        "https://198.51.100.1:8443/public/api/v1/accounts" \
        "end-to-end smoke test preserves a canonical dotted IPv4 gateway authority"

    new_harness chaos-ipv4-authority run-chaos-experiment.sh

    run_target "${TEST_ROOT}" run-chaos-experiment.sh \
        "LIFEOS_CHAOS_EXPERIMENT_WEBHOOK_URL=https://203.0.113.1:8443/experiments" \
        "LIFEOS_CHAOS_GATEWAY_HEALTH_URL=https://203.0.113.2:8444/management/actuator/health/readiness" \
        "LIFEOS_CHAOS_IDENTITY_HEALTH_URL=https://203.0.113.3:8445/management/actuator/health/readiness" \
        "LIFEOS_CHAOS_TASK_GOAL_HEALTH_URL=https://203.0.113.4:8446/management/actuator/health" \
        "GITHUB_RUN_ID=run-42"

    assert_status 0 "chaos experiment with canonical dotted IPv4 authorities"
    assert_log_contains "${TEST_ROOT}" \
        "https://203.0.113.1:8443/experiments" \
        "chaos experiment preserves a canonical dotted IPv4 webhook authority"
}

test_operational_urls_accept_dns_authorities_with_numeric_labels() {
    new_harness staging-dns-numeric-label staging-smoke-test.sh
    add_service_dockerfile "${TEST_ROOT}" example-service

    run_target "${TEST_ROOT}" staging-smoke-test.sh \
        'STAGING_SERVICE_HEALTH_URLS_JSON={"example-service":"https://4294967296.example.test:8443/private/actuator/health/readiness"}' \
        "FAKE_JQ_SERVICE_URL=https://4294967296.example.test:8443/private/actuator/health/readiness"

    assert_status 0 "staging smoke test with a DNS authority containing a numeric label"
    assert_log_contains "${TEST_ROOT}" \
        "https://4294967296.example.test:8443/private/actuator/health/readiness" \
        "staging smoke test preserves a DNS authority containing a numeric label"

    new_harness end-to-end-dns-numeric-label end-to-end-smoke-test.sh

    run_target "${TEST_ROOT}" end-to-end-smoke-test.sh \
        "LIFEOS_E2E_GATEWAY_BASE_URL=https://0x100000000.example.test:8443/public" \
        "LIFEOS_E2E_GATEWAY_MANAGEMENT_BASE_URL=https://gateway-management.example.test:8444/management" \
        "LIFEOS_E2E_IDENTITY_MANAGEMENT_BASE_URL=https://identity-management.example.test:8445/management" \
        "FAKE_CURL_ACCOUNT_REGISTRATION_STATUS_CODE=400"

    assert_status 0 "end-to-end smoke test with a DNS authority containing a numeric label"
    assert_log_contains "${TEST_ROOT}" \
        "https://0x100000000.example.test:8443/public/api/v1/accounts" \
        "end-to-end smoke test preserves a DNS authority containing a numeric label"

    new_harness chaos-dns-numeric-label run-chaos-experiment.sh

    run_target "${TEST_ROOT}" run-chaos-experiment.sh \
        "LIFEOS_CHAOS_EXPERIMENT_WEBHOOK_URL=https://040000000001.example.test:8443/experiments" \
        "LIFEOS_CHAOS_GATEWAY_HEALTH_URL=https://gateway-management.example.test:8444/management/actuator/health/readiness" \
        "LIFEOS_CHAOS_IDENTITY_HEALTH_URL=https://identity-management.example.test:8445/management/actuator/health/readiness" \
        "LIFEOS_CHAOS_TASK_GOAL_HEALTH_URL=https://task-goal.example.test:8446/management/actuator/health" \
        "GITHUB_RUN_ID=run-42"

    assert_status 0 "chaos experiment with a DNS authority containing a numeric label"
    assert_log_contains "${TEST_ROOT}" \
        "https://040000000001.example.test:8443/experiments" \
        "chaos experiment preserves a DNS authority containing a numeric label"
}

test_operational_urls_accept_valid_ipv4_embedded_ipv6_authorities() {
    new_harness staging-ipv4-embedded-ipv6-authority staging-smoke-test.sh
    add_service_dockerfile "${TEST_ROOT}" example-service

    run_target "${TEST_ROOT}" staging-smoke-test.sh \
        'STAGING_SERVICE_HEALTH_URLS_JSON={"example-service":"https://[::ffff:192.0.2.1]:8443/private/actuator/health/readiness"}' \
        "FAKE_JQ_SERVICE_URL=https://[::ffff:192.0.2.1]:8443/private/actuator/health/readiness"

    assert_status 0 "staging smoke test with a valid IPv4-embedded IPv6 authority"
    assert_log_contains "${TEST_ROOT}" \
        "https://[::ffff:192.0.2.1]:8443/private/actuator/health/readiness" \
        "staging smoke test preserves a valid IPv4-embedded IPv6 authority"

    new_harness end-to-end-ipv4-embedded-ipv6-authority end-to-end-smoke-test.sh

    run_target "${TEST_ROOT}" end-to-end-smoke-test.sh \
        "LIFEOS_E2E_GATEWAY_BASE_URL=https://[::ffff:192.0.2.1]:8443/public" \
        "LIFEOS_E2E_GATEWAY_MANAGEMENT_BASE_URL=https://[::ffff:192.0.2.2]:8444/management" \
        "LIFEOS_E2E_IDENTITY_MANAGEMENT_BASE_URL=https://[::ffff:192.0.2.3]:8445/management" \
        "FAKE_CURL_ACCOUNT_REGISTRATION_STATUS_CODE=400"

    assert_status 0 "end-to-end smoke test with valid IPv4-embedded IPv6 authorities"
    assert_log_contains "${TEST_ROOT}" \
        "https://[::ffff:192.0.2.1]:8443/public/api/v1/accounts" \
        "end-to-end smoke test preserves an IPv4-embedded IPv6 gateway authority"

    new_harness chaos-ipv4-embedded-ipv6-authority run-chaos-experiment.sh

    run_target "${TEST_ROOT}" run-chaos-experiment.sh \
        "LIFEOS_CHAOS_EXPERIMENT_WEBHOOK_URL=https://[::ffff:192.0.2.1]:8443/experiments" \
        "LIFEOS_CHAOS_GATEWAY_HEALTH_URL=https://[::ffff:192.0.2.2]:8444/management/actuator/health/readiness" \
        "LIFEOS_CHAOS_IDENTITY_HEALTH_URL=https://[::ffff:192.0.2.3]:8445/management/actuator/health/readiness" \
        "LIFEOS_CHAOS_TASK_GOAL_HEALTH_URL=https://[::ffff:192.0.2.4]:8446/management/actuator/health" \
        "GITHUB_RUN_ID=run-42"

    assert_status 0 "chaos experiment with valid IPv4-embedded IPv6 authorities"
    assert_log_contains "${TEST_ROOT}" \
        "https://[::ffff:192.0.2.1]:8443/experiments" \
        "chaos experiment preserves an IPv4-embedded IPv6 webhook authority"
}

test_health_checks_retry_down_responses_and_fail_closed() {
    new_harness staging-health-recovery staging-smoke-test.sh
    add_service_dockerfile "${TEST_ROOT}" example-service

    run_target "${TEST_ROOT}" staging-smoke-test.sh \
        'STAGING_SERVICE_HEALTH_URLS_JSON={"example-service":"https://staging.example.test/actuator/health/readiness"}' \
        "FAKE_JQ_SERVICE_URL=https://staging.example.test/actuator/health/readiness" \
        "FAKE_CURL_HEALTH_STATUS_SEQUENCE=DOWN,UP"

    assert_status 0 "staging smoke health recovery"
    assert_log_contains "${TEST_ROOT}" \
        $'curl\t--disable\t--fail\t--silent\t--show-error\t--location\t--max-redirs\t0\t--proto\t=https\t--connect-timeout\t10\t--max-time\t20\thttps://staging.example.test/actuator/health/readiness' \
        "staging smoke health probe disables curl configuration"
    assert_health_probe_count "${TEST_ROOT}" 2 "staging smoke health recovery"
    assert_log_line_count "${TEST_ROOT}" $'sleep\t' 1 "staging smoke health recovery backoff"
    assert_log_entry_excludes "${TEST_ROOT}" \
        "https://staging.example.test/actuator/health/readiness" \
        $'--retry\t' \
        "staging smoke explicit health retry loop"

    new_harness staging-health-persistent-down staging-smoke-test.sh
    add_service_dockerfile "${TEST_ROOT}" example-service

    run_target "${TEST_ROOT}" staging-smoke-test.sh \
        'STAGING_SERVICE_HEALTH_URLS_JSON={"example-service":"https://staging.example.test/actuator/health/readiness"}' \
        "FAKE_JQ_SERVICE_URL=https://staging.example.test/actuator/health/readiness" \
        "FAKE_CURL_HEALTH_STATUS_SEQUENCE=DOWN"

    assert_status 1 "staging smoke persistent DOWN response"
    assert_health_probe_count "${TEST_ROOT}" 6 "staging smoke persistent DOWN response"
    assert_log_line_count "${TEST_ROOT}" $'sleep\t' 5 "staging smoke persistent DOWN backoff"
    assert_file_contains "${RUN_OUTPUT}" \
        "Staging health for example-service did not report UP after 6 attempts" \
        "staging smoke persistent DOWN diagnostic"

    new_harness end-to-end-health-recovery end-to-end-smoke-test.sh

    run_target "${TEST_ROOT}" end-to-end-smoke-test.sh \
        "LIFEOS_E2E_GATEWAY_BASE_URL=https://gateway.example.test" \
        "LIFEOS_E2E_GATEWAY_MANAGEMENT_BASE_URL=https://gateway-management.example.test" \
        "LIFEOS_E2E_IDENTITY_MANAGEMENT_BASE_URL=https://identity-management.example.test" \
        "FAKE_CURL_HEALTH_STATUS_SEQUENCE=DOWN,UP,UP" \
        "FAKE_CURL_ACCOUNT_REGISTRATION_STATUS_CODE=400"

    assert_status 0 "end-to-end smoke health recovery"
    assert_log_contains "${TEST_ROOT}" \
        $'curl\t--disable\t--fail\t--silent\t--show-error\t--location\t--max-redirs\t0\t--proto\t=https\t--connect-timeout\t10\t--max-time\t20\thttps://gateway-management.example.test/actuator/health/readiness' \
        "end-to-end readiness probe disables curl configuration"
    assert_health_probe_count "${TEST_ROOT}" 3 "end-to-end smoke health recovery"
    assert_log_line_count "${TEST_ROOT}" $'sleep\t' 1 "end-to-end smoke health recovery backoff"
    assert_file_contains "${RUN_OUTPUT}" \
        "End-to-end gateway-to-identity contract passed" \
        "end-to-end smoke after health recovery"
    assert_log_contains "${TEST_ROOT}" \
        $'\t--output\t/dev/null\t--write-out\t%{http_code}\thttps://gateway.example.test/api/v1/accounts' \
        "end-to-end smoke discards the unused account-response body"
    assert_log_entry_excludes "${TEST_ROOT}" \
        "https://gateway.example.test/api/v1/accounts" \
        $'--retry\t' \
        "end-to-end smoke non-idempotent account request"

    new_harness end-to-end-health-persistent-down end-to-end-smoke-test.sh

    # The account-registration request is live-topology-only; a persistently DOWN prerequisite
    # must exhaust its bounded probe budget before that non-idempotent request can run.
    run_target "${TEST_ROOT}" end-to-end-smoke-test.sh \
        "LIFEOS_E2E_GATEWAY_BASE_URL=https://gateway.example.test" \
        "LIFEOS_E2E_GATEWAY_MANAGEMENT_BASE_URL=https://gateway-management.example.test" \
        "LIFEOS_E2E_IDENTITY_MANAGEMENT_BASE_URL=https://identity-management.example.test" \
        "FAKE_CURL_HEALTH_STATUS_SEQUENCE=DOWN"

    assert_status 1 "end-to-end smoke persistent DOWN response"
    assert_health_probe_count "${TEST_ROOT}" 6 "end-to-end smoke persistent DOWN response"
    assert_log_line_count "${TEST_ROOT}" $'sleep\t' 5 "end-to-end smoke persistent DOWN backoff"
    assert_log_excludes "${TEST_ROOT}" $'/api/v1/accounts' "end-to-end smoke after persistent DOWN"
}

test_health_checks_reject_redirects() {
    new_harness staging-health-redirect staging-smoke-test.sh
    add_service_dockerfile "${TEST_ROOT}" example-service

    run_target "${TEST_ROOT}" staging-smoke-test.sh \
        'STAGING_SERVICE_HEALTH_URLS_JSON={"example-service":"https://staging.example.test/actuator/health/readiness"}' \
        "FAKE_JQ_SERVICE_URL=https://staging.example.test/actuator/health/readiness" \
        "FAKE_CURL_REDIRECT_URL=https://staging.example.test/actuator/health/readiness" \
        "FAKE_CURL_REDIRECT_STATUS=302"

    assert_status 1 "staging smoke health probe receiving a redirect"
    assert_health_probe_count "${TEST_ROOT}" 6 "staging smoke redirect rejection"
    assert_log_contains "${TEST_ROOT}" \
        $'curl\t--disable\t--fail\t--silent\t--show-error\t--location\t--max-redirs\t0\t--proto\t=https\t--connect-timeout\t10\t--max-time\t20\thttps://staging.example.test/actuator/health/readiness' \
        "staging smoke health redirect bounds"
    assert_file_excludes "${RUN_OUTPUT}" "Staging health is UP for example-service" \
        "staging smoke must not report UP after a redirect"

    new_harness end-to-end-health-redirect end-to-end-smoke-test.sh

    run_target "${TEST_ROOT}" end-to-end-smoke-test.sh \
        "LIFEOS_E2E_GATEWAY_BASE_URL=https://gateway.example.test" \
        "LIFEOS_E2E_GATEWAY_MANAGEMENT_BASE_URL=https://gateway-management.example.test" \
        "LIFEOS_E2E_IDENTITY_MANAGEMENT_BASE_URL=https://identity-management.example.test" \
        "FAKE_CURL_REDIRECT_URL=https://gateway-management.example.test/actuator/health/readiness" \
        "FAKE_CURL_REDIRECT_STATUS=302"

    assert_status 1 "end-to-end readiness probe receiving a redirect"
    assert_health_probe_count "${TEST_ROOT}" 6 "end-to-end readiness redirect rejection"
    assert_log_contains "${TEST_ROOT}" \
        $'curl\t--disable\t--fail\t--silent\t--show-error\t--location\t--max-redirs\t0\t--proto\t=https\t--connect-timeout\t10\t--max-time\t20\thttps://gateway-management.example.test/actuator/health/readiness' \
        "end-to-end readiness redirect bounds"
    assert_file_excludes "${RUN_OUTPUT}" "End-to-end prerequisite is ready: gateway" \
        "end-to-end smoke must not report a redirecting prerequisite as ready"
    assert_file_excludes "${RUN_OUTPUT}" "End-to-end gateway-to-identity contract passed" \
        "end-to-end smoke must not execute its contract after a readiness redirect"

    new_harness chaos-health-redirect run-chaos-experiment.sh

    run_target "${TEST_ROOT}" run-chaos-experiment.sh \
        "LIFEOS_CHAOS_EXPERIMENT_WEBHOOK_URL=https://chaos.example.test/experiments" \
        "LIFEOS_CHAOS_GATEWAY_HEALTH_URL=https://gateway-management.example.test/actuator/health/readiness" \
        "LIFEOS_CHAOS_IDENTITY_HEALTH_URL=https://identity-management.example.test/actuator/health/readiness" \
        "LIFEOS_CHAOS_TASK_GOAL_HEALTH_URL=https://task-goal.example.test/actuator/health" \
        "GITHUB_RUN_ID=run-42" \
        "FAKE_CURL_REDIRECT_URL=https://gateway-management.example.test/actuator/health/readiness" \
        "FAKE_CURL_REDIRECT_STATUS=302"

    assert_status 1 "chaos recovery probe receiving a redirect"
    assert_health_probe_count "${TEST_ROOT}" 6 "chaos recovery redirect rejection"
    assert_log_contains "${TEST_ROOT}" \
        $'curl\t--disable\t--fail\t--silent\t--show-error\t--location\t--max-redirs\t0\t--proto\t=https\t--connect-timeout\t10\t--max-time\t20\thttps://gateway-management.example.test/actuator/health/readiness' \
        "chaos recovery redirect bounds"
    assert_file_excludes "${RUN_OUTPUT}" "Chaos experiment completed and all services recovered" \
        "chaos experiment must not report recovery after a redirect"
}

test_temporary_files_are_cleaned_on_normal_exit_and_signals() {
    local script temporary_directory started_file release_file
    local signal_name signal_slug expected_status case_name

    if ! command -p -v perl >/dev/null 2>&1; then
        fail "Perl is required to reset inherited signal dispositions in interruption regressions"
    fi

    # A normal successful exit proves the EXIT trap removes end-to-end's response-header buffer,
    # which is intentionally kept until the contract assertion completes.
    for script in staging-smoke-test.sh end-to-end-smoke-test.sh run-chaos-experiment.sh; do
        case_name="${script%.sh}-normal-temporary-file-cleanup"
        new_harness "${case_name}" "${script}"
        temporary_directory="${TEST_ROOT}/temporary-files"
        mkdir -p "${temporary_directory}"

        case "${script}" in
            staging-smoke-test.sh)
                add_service_dockerfile "${TEST_ROOT}" example-service
                run_target "${TEST_ROOT}" "${script}" \
                    "TMPDIR=${temporary_directory}" \
                    'STAGING_SERVICE_HEALTH_URLS_JSON={"example-service":"https://staging.example.test/actuator/health/readiness"}' \
                    "FAKE_JQ_SERVICE_URL=https://staging.example.test/actuator/health/readiness"
                ;;
            end-to-end-smoke-test.sh)
                run_target "${TEST_ROOT}" "${script}" \
                    "TMPDIR=${temporary_directory}" \
                    "LIFEOS_E2E_GATEWAY_BASE_URL=https://gateway.example.test" \
                    "LIFEOS_E2E_GATEWAY_MANAGEMENT_BASE_URL=https://gateway-management.example.test" \
                    "LIFEOS_E2E_IDENTITY_MANAGEMENT_BASE_URL=https://identity-management.example.test" \
                    "FAKE_CURL_ACCOUNT_REGISTRATION_STATUS_CODE=400"
                ;;
            run-chaos-experiment.sh)
                run_target "${TEST_ROOT}" "${script}" \
                    "TMPDIR=${temporary_directory}" \
                    "LIFEOS_CHAOS_EXPERIMENT_WEBHOOK_URL=https://chaos.example.test/experiments" \
                    "LIFEOS_CHAOS_GATEWAY_HEALTH_URL=https://gateway-management.example.test/actuator/health/readiness" \
                    "LIFEOS_CHAOS_IDENTITY_HEALTH_URL=https://identity-management.example.test/actuator/health/readiness" \
                    "LIFEOS_CHAOS_TASK_GOAL_HEALTH_URL=https://task-goal.example.test/actuator/health" \
                    "GITHUB_RUN_ID=run-42"
                ;;
        esac

        assert_status 0 "${script} normal temporary-file cleanup"
        assert_directory_empty "${temporary_directory}" \
            "${script} normal temporary-file cleanup"
    done

    # Run target scripts in the foreground while a helper sends each signal. Backgrounded Bash
    # children inherit an ignored SIGINT disposition, so this structure exercises the scripts'
    # actual INT handlers rather than a shell-specific background-job behavior.
    for signal_name in HUP INT TERM; do
        case "${signal_name}" in
            HUP)
                expected_status=129
                signal_slug=hup
                ;;
            INT)
                expected_status=130
                signal_slug=int
                ;;
            TERM)
                expected_status=143
                signal_slug=term
                ;;
        esac

        for script in staging-smoke-test.sh end-to-end-smoke-test.sh run-chaos-experiment.sh; do
            case_name="${script%.sh}-${signal_slug}-temporary-file-cleanup"
            new_harness "${case_name}" "${script}"
            temporary_directory="${TEST_ROOT}/temporary-files"
            started_file="${TEST_ROOT}/request-started"
            release_file="${TEST_ROOT}/request-release"
            mkdir -p "${temporary_directory}"

            case "${script}" in
                staging-smoke-test.sh)
                    add_service_dockerfile "${TEST_ROOT}" example-service
                    start_target "${TEST_ROOT}" "${script}" "${TEST_ROOT}/output.log" \
                        "FAKE_RESET_SIGNAL_DISPOSITIONS=true" \
                        "TMPDIR=${temporary_directory}" \
                        'STAGING_SERVICE_HEALTH_URLS_JSON={"example-service":"https://staging.example.test/actuator/health/readiness"}' \
                        "FAKE_JQ_SERVICE_URL=https://staging.example.test/actuator/health/readiness" \
                        "FAKE_CURL_HEALTH_BLOCK_STARTED_FILE=${started_file}" \
                        "FAKE_CURL_HEALTH_BLOCK_RELEASE_FILE=${release_file}"
                    ;;
                end-to-end-smoke-test.sh)
                    # Block after the health probes so the signal trap must clean the response
                    # header file rather than only a per-probe health buffer.
                    start_target "${TEST_ROOT}" "${script}" "${TEST_ROOT}/output.log" \
                        "FAKE_RESET_SIGNAL_DISPOSITIONS=true" \
                        "TMPDIR=${temporary_directory}" \
                        "LIFEOS_E2E_GATEWAY_BASE_URL=https://gateway.example.test" \
                        "LIFEOS_E2E_GATEWAY_MANAGEMENT_BASE_URL=https://gateway-management.example.test" \
                        "LIFEOS_E2E_IDENTITY_MANAGEMENT_BASE_URL=https://identity-management.example.test" \
                        "FAKE_CURL_ACCOUNT_BLOCK_STARTED_FILE=${started_file}" \
                        "FAKE_CURL_ACCOUNT_BLOCK_RELEASE_FILE=${release_file}"
                    ;;
                run-chaos-experiment.sh)
                    start_target "${TEST_ROOT}" "${script}" "${TEST_ROOT}/output.log" \
                        "FAKE_RESET_SIGNAL_DISPOSITIONS=true" \
                        "TMPDIR=${temporary_directory}" \
                        "LIFEOS_CHAOS_EXPERIMENT_WEBHOOK_URL=https://chaos.example.test/experiments" \
                        "LIFEOS_CHAOS_GATEWAY_HEALTH_URL=https://gateway-management.example.test/actuator/health/readiness" \
                        "LIFEOS_CHAOS_IDENTITY_HEALTH_URL=https://identity-management.example.test/actuator/health/readiness" \
                        "LIFEOS_CHAOS_TASK_GOAL_HEALTH_URL=https://task-goal.example.test/actuator/health" \
                        "GITHUB_RUN_ID=run-42" \
                        "FAKE_CURL_HEALTH_BLOCK_STARTED_FILE=${started_file}" \
                        "FAKE_CURL_HEALTH_BLOCK_RELEASE_FILE=${release_file}"
                    ;;
            esac

            if ! wait_for_file "${started_file}"; then
                fail "${script} must allocate and begin its blocked request before ${signal_name}"
            fi
            if [[ -z "$(command -p find "${temporary_directory}" -mindepth 1 -print -quit)" ]]; then
                fail "${script} must allocate a temporary file before ${signal_name}"
            fi

            kill "-${signal_name}" "${BACKGROUND_TARGET_PID}"
            : > "${release_file}"
            if wait "${BACKGROUND_TARGET_PID}" 2>/dev/null; then
                fail "${script} must stop after ${signal_name}"
            else
                RUN_STATUS=$?
            fi

            assert_status "${expected_status}" "${script} ${signal_name} exit status"
            assert_directory_empty "${temporary_directory}" \
                "${script} ${signal_name} temporary-file cleanup"
        done
    done
}

test_health_retry_jitter_remains_positive_when_random_is_zero() {
    new_harness staging-positive-jitter staging-smoke-test.sh
    add_service_dockerfile "${TEST_ROOT}" example-service

    run_target "${TEST_ROOT}" staging-smoke-test.sh \
        'STAGING_SERVICE_HEALTH_URLS_JSON={"example-service":"https://staging.example.test/actuator/health/readiness"}' \
        "FAKE_JQ_SERVICE_URL=https://staging.example.test/actuator/health/readiness" \
        "FAKE_CURL_HEALTH_STATUS_SEQUENCE=DOWN,UP" \
        "FAKE_BASH_RANDOM_SEED=0"

    assert_status 0 "staging smoke positive jitter with RANDOM=0"
    assert_log_line_count "${TEST_ROOT}" $'sleep\t1' 1 \
        "staging smoke positive jitter with RANDOM=0"

    new_harness end-to-end-positive-jitter end-to-end-smoke-test.sh

    run_target "${TEST_ROOT}" end-to-end-smoke-test.sh \
        "LIFEOS_E2E_GATEWAY_BASE_URL=https://gateway.example.test" \
        "LIFEOS_E2E_GATEWAY_MANAGEMENT_BASE_URL=https://gateway-management.example.test" \
        "LIFEOS_E2E_IDENTITY_MANAGEMENT_BASE_URL=https://identity-management.example.test" \
        "FAKE_CURL_ACCOUNT_REGISTRATION_STATUS_CODE=400" \
        "FAKE_CURL_HEALTH_STATUS_SEQUENCE=DOWN,UP,UP" \
        "FAKE_BASH_RANDOM_SEED=0"

    assert_status 0 "end-to-end smoke positive jitter with RANDOM=0"
    assert_log_line_count "${TEST_ROOT}" $'sleep\t1' 1 \
        "end-to-end smoke positive jitter with RANDOM=0"

    new_harness chaos-positive-jitter run-chaos-experiment.sh

    run_target "${TEST_ROOT}" run-chaos-experiment.sh \
        "LIFEOS_CHAOS_EXPERIMENT_WEBHOOK_URL=https://chaos.example.test/experiments" \
        "LIFEOS_CHAOS_GATEWAY_HEALTH_URL=https://gateway-management.example.test/actuator/health/readiness" \
        "LIFEOS_CHAOS_IDENTITY_HEALTH_URL=https://identity-management.example.test/actuator/health/readiness" \
        "LIFEOS_CHAOS_TASK_GOAL_HEALTH_URL=https://task-goal.example.test/actuator/health" \
        "GITHUB_RUN_ID=run-42" \
        "FAKE_CURL_HEALTH_STATUS_SEQUENCE=DOWN,UP,UP,UP" \
        "FAKE_BASH_RANDOM_SEED=0"

    assert_status 0 "chaos experiment positive jitter with RANDOM=0"
    assert_log_line_count "${TEST_ROOT}" $'sleep\t1' 1 \
        "chaos experiment positive jitter with RANDOM=0"
}

test_health_checks_bound_chunked_response_bodies() {
    # The first 64 KiB are valid JSON plus whitespace; the 65,537th byte is invalid trailing data.
    # The fake curl double emits no Content-Length metadata, so each case exercises the cap-plus-one
    # sentinel instead of relying on curl to know the response length before the transfer.
    local oversized_response_bytes=65537

    new_harness staging-health-oversized-chunked staging-smoke-test.sh
    add_service_dockerfile "${TEST_ROOT}" example-service

    run_target "${TEST_ROOT}" staging-smoke-test.sh \
        'STAGING_SERVICE_HEALTH_URLS_JSON={"example-service":"https://staging.example.test/actuator/health/readiness"}' \
        "FAKE_JQ_SERVICE_URL=https://staging.example.test/actuator/health/readiness" \
        "FAKE_CURL_CHUNKED_HEALTH_VALID_PREFIX_RESPONSE_BYTES=${oversized_response_bytes}"

    assert_status 1 "staging smoke oversized chunked health response"
    assert_health_probe_count "${TEST_ROOT}" 6 "staging smoke oversized chunked health response"
    assert_file_contains "${RUN_OUTPUT}" \
        "Staging health for example-service did not report UP after 6 attempts" \
        "staging smoke oversized chunked health diagnostic"
    assert_log_excludes "${TEST_ROOT}" $'jq\t--exit-status\t.status == "UP"' \
        "staging smoke must reject an oversized body before jq"

    new_harness end-to-end-health-oversized-chunked end-to-end-smoke-test.sh

    run_target "${TEST_ROOT}" end-to-end-smoke-test.sh \
        "LIFEOS_E2E_GATEWAY_BASE_URL=https://gateway.example.test" \
        "LIFEOS_E2E_GATEWAY_MANAGEMENT_BASE_URL=https://gateway-management.example.test" \
        "LIFEOS_E2E_IDENTITY_MANAGEMENT_BASE_URL=https://identity-management.example.test" \
        "FAKE_CURL_CHUNKED_HEALTH_VALID_PREFIX_RESPONSE_BYTES=${oversized_response_bytes}"

    assert_status 1 "end-to-end smoke oversized chunked health response"
    assert_health_probe_count "${TEST_ROOT}" 6 "end-to-end smoke oversized chunked health response"
    assert_file_contains "${RUN_OUTPUT}" \
        "End-to-end prerequisite gateway did not report UP after 6 attempts" \
        "end-to-end oversized chunked health diagnostic"
    assert_log_excludes "${TEST_ROOT}" $'jq\t--exit-status\t.status == "UP"' \
        "end-to-end smoke must reject an oversized body before jq"
    assert_log_excludes "${TEST_ROOT}" $'/api/v1/accounts' \
        "end-to-end smoke after an oversized chunked health response"

    new_harness chaos-health-oversized-chunked run-chaos-experiment.sh

    run_target "${TEST_ROOT}" run-chaos-experiment.sh \
        "LIFEOS_CHAOS_EXPERIMENT_WEBHOOK_URL=https://chaos.example.test/experiments" \
        "LIFEOS_CHAOS_GATEWAY_HEALTH_URL=https://gateway-management.example.test/actuator/health/readiness" \
        "LIFEOS_CHAOS_IDENTITY_HEALTH_URL=https://identity-management.example.test/actuator/health/readiness" \
        "LIFEOS_CHAOS_TASK_GOAL_HEALTH_URL=https://task-goal.example.test/actuator/health" \
        "GITHUB_RUN_ID=run-42" \
        "FAKE_CURL_CHUNKED_HEALTH_VALID_PREFIX_RESPONSE_BYTES=${oversized_response_bytes}"

    assert_status 1 "chaos experiment oversized chunked health response"
    assert_health_probe_count "${TEST_ROOT}" 6 "chaos experiment oversized chunked health response"
    assert_file_contains "${RUN_OUTPUT}" \
        "Chaos recovery health for gateway did not report UP after 6 attempts" \
        "chaos oversized chunked health diagnostic"
    assert_log_excludes "${TEST_ROOT}" $'jq\t--exit-status\t.status == "UP"' \
        "chaos experiment must reject an oversized body before jq"
}

test_chaos_experiment_uses_bounded_payload_transport_and_recovery_probes() {
    new_harness chaos-experiment run-chaos-experiment.sh

    run_target "${TEST_ROOT}" run-chaos-experiment.sh \
        "LIFEOS_CHAOS_EXPERIMENT_WEBHOOK_URL=https://chaos.example.test/experiments" \
        "LIFEOS_CHAOS_GATEWAY_HEALTH_URL=https://gateway-management.example.test/actuator/health/readiness" \
        "LIFEOS_CHAOS_IDENTITY_HEALTH_URL=https://identity-management.example.test/actuator/health/readiness" \
        "LIFEOS_CHAOS_TASK_GOAL_HEALTH_URL=https://task-goal.example.test/actuator/health" \
        "GITHUB_RUN_ID=run-42" \
        "FAKE_CURL_HEALTH_STATUS_SEQUENCE=DOWN,UP,UP,UP"

    assert_status 0 "chaos experiment with successful recovery probes"
    assert_log_contains "${TEST_ROOT}" \
        $'curl\t--disable\t--fail\t--silent\t--show-error\t--location\t--max-redirs\t0\t--proto\t=https\t--connect-timeout\t10\t--max-time\t300' \
        "chaos experiment bounded webhook transport"
    assert_log_contains "${TEST_ROOT}" \
        $'curl\t--disable\t--fail\t--silent\t--show-error\t--location\t--max-redirs\t0\t--proto\t=https\t--connect-timeout\t10\t--max-time\t20\thttps://gateway-management.example.test/actuator/health/readiness' \
        "chaos recovery probe disables curl configuration"
    assert_log_entry_excludes "${TEST_ROOT}" \
        "https://chaos.example.test/experiments" \
        $'--retry\t' \
        "chaos experiment non-idempotent webhook"
    assert_log_contains "${TEST_ROOT}" \
        $'jq\t-cn\t--arg\trunId\trun-42\t--arg\texperiment\tdependency-isolation-readiness\t--arg\tgateway\thttps://gateway-management.example.test/actuator/health/readiness\t--arg\tidentity\thttps://identity-management.example.test/actuator/health/readiness\t--arg\ttaskGoal\thttps://task-goal.example.test/actuator/health' \
        "chaos experiment payload inputs"
    assert_log_contains "${TEST_ROOT}" \
        $'\t--header\tContent-Type: application/json\t--data\t{"mock":true}\t--output\t/dev/null\thttps://chaos.example.test/experiments' \
        "chaos experiment webhook payload"
    assert_health_probe_count "${TEST_ROOT}" 4 \
        "chaos experiment recovery probes after a DOWN response"
    assert_log_line_count "${TEST_ROOT}" $'sleep\t' 1 \
        "chaos experiment recovery backoff"
}

test_chaos_experiment_rejects_userinfo_before_payload_or_probes() {
    local invalid_setting
    local case_number=0
    for invalid_setting in \
        "LIFEOS_CHAOS_EXPERIMENT_WEBHOOK_URL=https://user:password@chaos.example.test/experiments" \
        "LIFEOS_CHAOS_GATEWAY_HEALTH_URL=https://user:password@gateway-management.example.test/actuator/health/readiness" \
        "LIFEOS_CHAOS_IDENTITY_HEALTH_URL=https://user:password@identity-management.example.test/actuator/health/readiness" \
        "LIFEOS_CHAOS_TASK_GOAL_HEALTH_URL=https://user:password@task-goal.example.test/actuator/health"; do
        ((case_number += 1))
        new_harness "chaos-userinfo-${case_number}" run-chaos-experiment.sh

        run_target "${TEST_ROOT}" run-chaos-experiment.sh \
            "LIFEOS_CHAOS_EXPERIMENT_WEBHOOK_URL=https://chaos.example.test/experiments" \
            "LIFEOS_CHAOS_GATEWAY_HEALTH_URL=https://gateway-management.example.test/actuator/health/readiness" \
            "LIFEOS_CHAOS_IDENTITY_HEALTH_URL=https://identity-management.example.test/actuator/health/readiness" \
            "LIFEOS_CHAOS_TASK_GOAL_HEALTH_URL=https://task-goal.example.test/actuator/health" \
            "${invalid_setting}"

        assert_status 64 "chaos experiment with userinfo in endpoint ${case_number}"
        assert_no_commands_logged "${TEST_ROOT}" \
            "chaos experiment with userinfo must not construct a payload or probe services"
    done
}

test_chaos_experiment_fails_for_webhook_and_recovery_errors() {
    new_harness chaos-webhook-failure run-chaos-experiment.sh

    run_target "${TEST_ROOT}" run-chaos-experiment.sh \
        "LIFEOS_CHAOS_EXPERIMENT_WEBHOOK_URL=https://chaos.example.test/experiments" \
        "LIFEOS_CHAOS_GATEWAY_HEALTH_URL=https://gateway-management.example.test/actuator/health/readiness" \
        "LIFEOS_CHAOS_IDENTITY_HEALTH_URL=https://identity-management.example.test/actuator/health/readiness" \
        "LIFEOS_CHAOS_TASK_GOAL_HEALTH_URL=https://task-goal.example.test/actuator/health" \
        "FAKE_CURL_STATUS=22"

    assert_status 22 "chaos experiment with a non-2xx webhook response"
    assert_log_line_count "${TEST_ROOT}" $'curl\t' 1 \
        "chaos experiment after a failed webhook"
    assert_health_probe_count "${TEST_ROOT}" 0 \
        "chaos experiment after a failed webhook"

    new_harness chaos-recovery-failure run-chaos-experiment.sh

    run_target "${TEST_ROOT}" run-chaos-experiment.sh \
        "LIFEOS_CHAOS_EXPERIMENT_WEBHOOK_URL=https://chaos.example.test/experiments" \
        "LIFEOS_CHAOS_GATEWAY_HEALTH_URL=https://gateway-management.example.test/private-capability/actuator/health/readiness" \
        "LIFEOS_CHAOS_IDENTITY_HEALTH_URL=https://identity-management.example.test/actuator/health/readiness" \
        "LIFEOS_CHAOS_TASK_GOAL_HEALTH_URL=https://task-goal.example.test/actuator/health" \
        "FAKE_CURL_HEALTH_STATUS_SEQUENCE=DOWN"

    assert_status 1 "chaos experiment with a recovery probe that is not UP"
    assert_health_probe_count "${TEST_ROOT}" 6 \
        "chaos experiment after a persistently DOWN recovery probe"
    assert_log_line_count "${TEST_ROOT}" $'sleep\t' 5 \
        "chaos experiment persistent DOWN backoff"
    assert_file_contains "${RUN_OUTPUT}" \
        "Chaos recovery health for gateway did not report UP after 6 attempts" \
        "chaos recovery failure stable target diagnostic"
    assert_file_excludes "${RUN_OUTPUT}" "private-capability" \
        "chaos recovery failure must not expose a configured health path"
    assert_log_excludes "${TEST_ROOT}" \
        $'curl\t--disable\t--fail\t--silent\t--show-error\t--location\t--max-redirs\t0\t--proto\t=https\t--connect-timeout\t10\t--max-time\t20\thttps://identity-management.example.test/actuator/health/readiness' \
        "chaos experiment after a failed gateway recovery probe"
}

test_file_assertions_match_full_file_literals
test_file_assertions_fail_clearly_when_files_are_missing
test_log_assertions_fail_clearly_for_invalid_patterns_and_missing_logs
test_command_double_dispatch_fails_closed
test_run_target_exports_only_valid_environment_assignments
test_build_rejects_missing_services
test_build_rejects_missing_or_ambiguous_jars
test_build_passes_jar_argument_and_honors_push_switch
test_container_build_validates_and_bounds_docker_operations
test_container_build_selects_portable_timeout_commands
test_container_scripts_reject_invalid_generated_image_references
test_container_scripts_enforce_docker_repository_name_length
test_container_scan_rejects_missing_images_and_passes_trivy_arguments
test_container_scan_uses_a_csv_quoted_mount_for_comma_cache_paths
test_container_scan_requires_an_accessible_docker_daemon
test_source_scan_uses_read_only_repository_mount_and_filesystem_arguments
test_source_scan_uses_an_unambiguous_read_only_mount_for_colon_repository_paths
test_source_scan_uses_a_csv_quoted_mount_for_comma_repository_paths
test_source_scan_requires_an_accessible_docker_daemon
test_security_scans_require_an_absolute_trivy_cache_directory
test_security_scans_validate_and_bound_docker_operations
test_security_scans_select_portable_timeout_commands
test_security_scans_serialize_shared_trivy_cache_access
test_container_scan_releases_trivy_cache_lock_between_services
test_security_scans_fail_fast_when_the_trivy_cache_lock_cannot_be_created
test_security_scans_reject_symlinked_trivy_cache_locks_without_waiting
test_security_scans_retry_after_a_trivy_cache_lock_release_race
test_security_scans_ignore_untrusted_trivy_image_overrides
test_database_provisioning_waits_before_exec_and_handles_failures
test_database_provisioning_requires_the_compose_plugin
test_database_provisioning_requires_a_supported_compose_version
test_database_provisioning_rejects_unbounded_timeout
test_database_provisioning_sql_keeps_create_queries_open_for_gexec
test_concurrent_database_provisioning_pins_its_default_image_and_honors_override
test_concurrent_database_provisioning_requires_a_bounded_observation_timeout
test_concurrent_database_provisioning_reports_advisory_lock_validation_cleanly
test_verifier_repository_root_resolution_fails_closed
test_performance_smoke_accepts_100_vus_and_prefers_k6
test_performance_smoke_docker_fallback_uses_read_only_repository_mount
test_performance_smoke_rejects_escaped_summary_paths
test_performance_smoke_bounds_summary_path_input
test_performance_smoke_rejects_invalid_vus_values
test_deploy_staging_rejects_unsafe_webhooks_and_uses_bounded_transport
test_service_discovery_requires_its_dependencies
test_container_service_discovery_fails_closed_after_partial_output
test_container_service_discovery_preserves_no_dockerfiles_behavior
test_staging_service_discovery_fails_closed_after_partial_output
test_staging_service_discovery_preserves_no_dockerfiles_behavior
test_staging_scripts_require_dirname_before_resolving_repository_root
test_container_build_requires_dirname_before_resolving_repository_root
test_security_scan_scripts_require_dirname_before_resolving_repository_root
test_retry_utilities_are_preflighted_before_operational_paths
test_contract_sensitive_posts_reject_redirects
test_staging_and_end_to_end_smoke_fail_closed_before_live_traffic
test_end_to_end_smoke_accepts_correlation_headers_without_optional_whitespace
test_end_to_end_smoke_reports_header_buffer_allocation_failures
test_operational_scripts_share_https_authority_and_health_helpers
test_operational_urls_reject_userinfo_before_live_traffic
test_operational_urls_reject_malformed_authorities_before_live_traffic
test_operational_urls_accept_explicit_valid_ports_and_paths
test_operational_urls_accept_valid_bracketed_ipv6_authorities
test_operational_urls_accept_canonical_dotted_ipv4_authorities
test_operational_urls_accept_dns_authorities_with_numeric_labels
test_operational_urls_accept_valid_ipv4_embedded_ipv6_authorities
test_health_checks_retry_down_responses_and_fail_closed
test_health_checks_reject_redirects
test_temporary_files_are_cleaned_on_normal_exit_and_signals
test_health_retry_jitter_remains_positive_when_random_is_zero
test_health_checks_bound_chunked_response_bodies
test_chaos_experiment_uses_bounded_payload_transport_and_recovery_probes
test_chaos_experiment_rejects_userinfo_before_payload_or_probes
test_chaos_experiment_fails_for_webhook_and_recovery_errors

printf '%s\n' 'Operational script behavioral tests passed'
