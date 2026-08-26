#!/usr/bin/env bash
set -euo pipefail

# This file also acts as the deterministic command double used by the tests below. Each harness
# places symlinks named docker, jq, curl, k6, rg, and sleep in PATH, so the operational scripts
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
        if [[ -n "${FAKE_CURL_HEALTH_STATUS_SEQUENCE:-}" ]]; then
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
        if [[ -n "${dump_header_file}" ]]; then
            printf 'HTTP/1.1 %s Response\r\nX-Correlation-ID: %s\r\n\r\n' \
                "${FAKE_CURL_ACCOUNT_REGISTRATION_STATUS_CODE}" \
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

    if [[ -n "${FAKE_CURL_STDOUT:-}" ]]; then
        printf '%s\n' "${FAKE_CURL_STDOUT}"
    fi
    return "${FAKE_CURL_STATUS:-0}"
}

fake_rg() {
    fake_log_command rg "$@"
    return "${FAKE_RG_STATUS:-0}"
}

fake_sleep() {
    fake_log_command sleep "$@"
    return "${FAKE_SLEEP_STATUS:-0}"
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

    local script
    for script in "$@"; do
        mkdir -p "${TEST_ROOT}/scripts/$(dirname "${script}")"
        cp "${REPOSITORY_ROOT}/scripts/${script}" "${TEST_ROOT}/scripts/${script}"
    done

    local command
    for command in docker jq curl k6 rg sleep; do
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

run_target() {
    local root="$1"
    local script="$2"
    shift 2

    RUN_OUTPUT="${root}/output.log"
    : > "${root}/commands.log"
    if (
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
            FAKE_DOCKER_STDIN_LOG \
            FAKE_DOCKER_STATUS \
            FAKE_CURL_STATUS \
            FAKE_CURL_STDOUT \
            FAKE_CURL_REDIRECT_FINAL_CORRELATION_ID \
            FAKE_CURL_REDIRECT_INTERMEDIATE_CORRELATION_ID \
            FAKE_CURL_REDIRECT_STATUS \
            FAKE_CURL_REDIRECT_URL \
            FAKE_CURL_ACCOUNT_REGISTRATION_CORRELATION_ID \
            FAKE_CURL_ACCOUNT_REGISTRATION_STATUS_CODE \
            FAKE_CURL_HEALTH_STATUS_SEQUENCE \
            FAKE_DIRNAME_OUTPUT \
            FAKE_FIND_PARTIAL_OUTPUT \
            FAKE_FIND_STATUS \
            FAKE_JQ_READINESS_STATUS \
            FAKE_JQ_SERVICE_URL \
            FAKE_RG_STATUS \
            FAKE_SLEEP_STATUS \
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
        bash "${root}/scripts/${script}" "$@"
    ) > "${RUN_OUTPUT}" 2>&1; then
        RUN_STATUS=0
    else
        RUN_STATUS=$?
    fi
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

test_container_scripts_enforce_docker_repository_path_length() {
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

    # A Docker registry is not part of the 255-character repository-path limit. Four 60-character
    # labels make the complete reference longer than 255 while retaining a syntactically valid,
    # DNS-compatible registry name and a short repository path.
    printf -v registry_label '%*s' 60 ''
    registry_label="${registry_label// /r}"
    long_registry="${registry_label}.${registry_label}.${registry_label}.${registry_label}"
    image_prefix="${long_registry}/lifeos"

    new_harness build-long-registry-short-repository build-container-images.sh
    add_service_dockerfile "${TEST_ROOT}" example-service
    add_service_jar "${TEST_ROOT}" example-service example-service.jar

    run_target "${TEST_ROOT}" build-container-images.sh \
        "LIFEOS_IMAGE_PREFIX=${image_prefix}"

    assert_status 0 "container build with a long registry and short repository path"
    assert_log_contains "${TEST_ROOT}" $'docker\tbuild\t' \
        "container build with a long registry and short repository path"

    new_harness scan-long-registry-short-repository scan-container-images.sh
    add_service_dockerfile "${TEST_ROOT}" example-service

    run_target "${TEST_ROOT}" scan-container-images.sh \
        "LIFEOS_IMAGE_PREFIX=${image_prefix}" \
        "LIFEOS_TRIVY_CACHE_DIR=${TEST_ROOT}/trivy-cache"

    assert_status 0 "container scan with a long registry and short repository path"
    assert_log_contains "${TEST_ROOT}" $'docker\tinfo' \
        "container scan with a long registry and short repository path"
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
        $'docker\trun\t--rm\t--volume\t'"${cache_dir}"$':/root/.cache\t--volume\t/var/run/docker.sock:/var/run/docker.sock' \
        "container scan mounts"
    assert_log_contains "${TEST_ROOT}" \
        $'\timage\t--no-progress\t--exit-code\t1\t--ignore-unfixed\t--severity\tHIGH,CRITICAL\tlifeos/example-service:scan-42' \
        "container scan Trivy image arguments"
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
        $'docker\trun\t--rm\t--volume\t'"${cache_dir}"$':/root/.cache\t--volume\t'"${TEST_ROOT}"$':/repo:ro\t--workdir\t/repo' \
        "source security scan mounts"
    assert_log_contains "${TEST_ROOT}" \
        $'\tfs\t--no-progress\t--exit-code\t1\t--ignore-unfixed\t--scanners\tvuln,secret,misconfig\t--severity\tHIGH,CRITICAL\t--skip-dirs\t.git\t--skip-dirs\t.gradle\t.' \
        "source security scan Trivy filesystem arguments"
    assert_log_order "${TEST_ROOT}" \
        $'docker\tinfo' \
        $'docker\trun\t' \
        "source security scan daemon preflight ordering"
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
        add_service_discovery_prerequisites_except "${TEST_ROOT}" "${missing_command}" cat

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
    add_service_discovery_prerequisites_except "${TEST_ROOT}" unavailable-command cat
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
        $'curl\t--disable\t--fail\t--silent\t--show-error\t--location\t--proto\t=https\t--connect-timeout\t10\t--max-time\t20\thttps://gateway-management.example.test/actuator/health/readiness' \
        "end-to-end readiness redirects remain enabled"

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

test_health_checks_retry_down_responses_and_fail_closed() {
    new_harness staging-health-recovery staging-smoke-test.sh
    add_service_dockerfile "${TEST_ROOT}" example-service

    run_target "${TEST_ROOT}" staging-smoke-test.sh \
        'STAGING_SERVICE_HEALTH_URLS_JSON={"example-service":"https://staging.example.test/actuator/health/readiness"}' \
        "FAKE_JQ_SERVICE_URL=https://staging.example.test/actuator/health/readiness" \
        "FAKE_CURL_HEALTH_STATUS_SEQUENCE=DOWN,UP"

    assert_status 0 "staging smoke health recovery"
    assert_log_contains "${TEST_ROOT}" \
        $'curl\t--disable\t--fail\t--silent\t--show-error\t--location\t--proto\t=https\t--connect-timeout\t10\t--max-time\t20\thttps://staging.example.test/actuator/health/readiness' \
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
        $'curl\t--disable\t--fail\t--silent\t--show-error\t--location\t--proto\t=https\t--connect-timeout\t10\t--max-time\t20\thttps://gateway-management.example.test/actuator/health/readiness' \
        "end-to-end readiness probe disables curl configuration"
    assert_health_probe_count "${TEST_ROOT}" 3 "end-to-end smoke health recovery"
    assert_log_line_count "${TEST_ROOT}" $'sleep\t' 1 "end-to-end smoke health recovery backoff"
    assert_file_contains "${RUN_OUTPUT}" \
        "End-to-end gateway-to-identity contract passed" \
        "end-to-end smoke after health recovery"
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
        $'curl\t--disable\t--fail\t--silent\t--show-error\t--location\t--proto\t=https\t--connect-timeout\t10\t--max-time\t20\thttps://gateway-management.example.test/actuator/health/readiness' \
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
        "LIFEOS_CHAOS_GATEWAY_HEALTH_URL=https://gateway-management.example.test/actuator/health/readiness" \
        "LIFEOS_CHAOS_IDENTITY_HEALTH_URL=https://identity-management.example.test/actuator/health/readiness" \
        "LIFEOS_CHAOS_TASK_GOAL_HEALTH_URL=https://task-goal.example.test/actuator/health" \
        "FAKE_CURL_HEALTH_STATUS_SEQUENCE=DOWN"

    assert_status 1 "chaos experiment with a recovery probe that is not UP"
    assert_health_probe_count "${TEST_ROOT}" 6 \
        "chaos experiment after a persistently DOWN recovery probe"
    assert_log_line_count "${TEST_ROOT}" $'sleep\t' 5 \
        "chaos experiment persistent DOWN backoff"
    assert_log_excludes "${TEST_ROOT}" \
        $'curl\t--disable\t--fail\t--silent\t--show-error\t--location\t--proto\t=https\t--connect-timeout\t10\t--max-time\t20\thttps://identity-management.example.test/actuator/health/readiness' \
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
test_container_scripts_reject_invalid_generated_image_references
test_container_scripts_enforce_docker_repository_path_length
test_container_scan_rejects_missing_images_and_passes_trivy_arguments
test_container_scan_requires_an_accessible_docker_daemon
test_source_scan_uses_read_only_repository_mount_and_filesystem_arguments
test_source_scan_requires_an_accessible_docker_daemon
test_security_scans_ignore_untrusted_trivy_image_overrides
test_database_provisioning_waits_before_exec_and_handles_failures
test_database_provisioning_requires_the_compose_plugin
test_database_provisioning_requires_a_supported_compose_version
test_database_provisioning_rejects_unbounded_timeout
test_database_provisioning_sql_keeps_create_queries_open_for_gexec
test_concurrent_database_provisioning_pins_its_default_image_and_honors_override
test_verifier_repository_root_resolution_fails_closed
test_performance_smoke_accepts_100_vus_and_prefers_k6
test_performance_smoke_docker_fallback_uses_read_only_repository_mount
test_performance_smoke_rejects_escaped_summary_paths
test_performance_smoke_rejects_invalid_vus_values
test_deploy_staging_rejects_unsafe_webhooks_and_uses_bounded_transport
test_service_discovery_requires_its_dependencies
test_staging_service_discovery_fails_closed_after_partial_output
test_staging_service_discovery_preserves_no_dockerfiles_behavior
test_staging_scripts_require_dirname_before_resolving_repository_root
test_contract_sensitive_posts_reject_redirects
test_staging_and_end_to_end_smoke_fail_closed_before_live_traffic
test_operational_urls_reject_userinfo_before_live_traffic
test_health_checks_retry_down_responses_and_fail_closed
test_chaos_experiment_uses_bounded_payload_transport_and_recovery_probes
test_chaos_experiment_rejects_userinfo_before_payload_or_probes
test_chaos_experiment_fails_for_webhook_and_recovery_errors

printf '%s\n' 'Operational script behavioral tests passed'
