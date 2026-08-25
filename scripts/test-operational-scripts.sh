#!/usr/bin/env bash
set -euo pipefail

# This file also acts as the deterministic command double used by the tests below. Each harness
# places symlinks named docker, jq, curl, k6, and rg in PATH, so the operational scripts
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

fake_docker() {
    fake_log_command docker "$@"

    if [[ "${1:-}" == "info" ]]; then
        return "${FAKE_DOCKER_INFO_STATUS:-0}"
    fi

    if [[ "${1:-}" == "image" && "${2:-}" == "inspect" ]]; then
        return "${FAKE_DOCKER_IMAGE_INSPECT_STATUS:-0}"
    fi

    if [[ "${1:-}" == "compose" && "${2:-}" == "version" ]]; then
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
        if ! command -p -v jq >/dev/null 2>&1; then
            printf '%s\n' 'System jq is required to validate staging smoke JSON fixtures' >&2
            return 69
        fi

        local validation_input
        validation_input="$(cat)"
        if ! command -p jq --exit-status 'type == "object" and all(.[]; type == "string")' \
            <<< "${validation_input}" >/dev/null 2>&1; then
            return 64
        fi
        return 0
    fi

    if [[ " $* " == *" --raw-output "* && -n "${FAKE_JQ_SERVICE_URL:-}" ]]; then
        printf '%s\n' "${FAKE_JQ_SERVICE_URL}"
        return 0
    fi

    if [[ "${FAKE_JQ_READINESS_STATUS:-0}" != "0" && "$*" == *'.status == "UP"'* ]]; then
        return "${FAKE_JQ_READINESS_STATUS}"
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

    local argument dump_header_file="" redirect_url="" url=""
    local follows_redirect=false
    local maximum_redirects=""
    for ((argument_index = 1; argument_index <= $#; argument_index += 1)); do
        argument="${!argument_index}"
        case "${argument}" in
            --dump-header)
                ((argument_index += 1))
                dump_header_file="${!argument_index}"
                ;;
            --location)
                follows_redirect=true
                ;;
            --max-redirs)
                ((argument_index += 1))
                maximum_redirects="${!argument_index}"
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

    if [[ -n "${FAKE_CURL_STDOUT:-}" ]]; then
        printf '%s\n' "${FAKE_CURL_STDOUT}"
    fi
    return "${FAKE_CURL_STATUS:-0}"
}

fake_rg() {
    fake_log_command rg "$@"
    return "${FAKE_RG_STATUS:-0}"
}

fake_dirname() {
    fake_log_command dirname "$@"
    printf '%s\n' "${FAKE_DIRNAME_OUTPUT:-/definitely-missing-lifeos-script-directory}"
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

case "$(basename "$0")" in
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
    dirname)
        fake_dirname "$@"
        exit
        ;;
esac

SCRIPT_DIRECTORY="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_DIRECTORY
REPOSITORY_ROOT="$(cd -- "${SCRIPT_DIRECTORY}/.." && pwd)"
readonly REPOSITORY_ROOT
TEST_SCRIPT_PATH="${SCRIPT_DIRECTORY}/$(basename "${BASH_SOURCE[0]}")"
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
    if ! grep -Fq -- "${expected}" "${file}"; then
        fail "${description}: missing '${expected}'"
    fi
}

assert_file_excludes() {
    local file="$1"
    local unexpected="$2"
    local description="$3"
    if grep -Fq -- "${unexpected}" "${file}"; then
        fail "${description}: found unexpected '${unexpected}'"
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

    assert_file_contains "${log_file}" "${entry_marker}" "${description} command"
    local matching_entries
    matching_entries="$(grep -F -- "${entry_marker}" "${log_file}")"
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

    actual_count="$(grep -F -c -- "${expected}" "${root}/commands.log" || true)"
    if [[ "${actual_count}" -ne "${expected_count}" ]]; then
        fail "${description}: expected ${expected_count} matching commands, got ${actual_count}"
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
    for command in docker jq curl k6 rg; do
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

run_target() {
    local root="$1"
    local script="$2"
    shift 2

    RUN_OUTPUT="${root}/output.log"
    : > "${root}/commands.log"
    if (
        export PATH="${root}/bin:${PATH}"
        export FAKE_COMMAND_LOG="${root}/commands.log"
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
            LIFEOS_OPERATIONAL_TEST_NO_NATIVE_K6 \
            LIFEOS_PUSH_IMAGES \
            LIFEOS_TRIVY_CACHE_DIR \
            LIFEOS_TRIVY_IMAGE \
            FAKE_DOCKER_COMPOSE_VERSION_STATUS \
            FAKE_DOCKER_INFO_STATUS \
            FAKE_DOCKER_STDIN_LOG \
            FAKE_CURL_REDIRECT_FINAL_CORRELATION_ID \
            FAKE_CURL_REDIRECT_INTERMEDIATE_CORRELATION_ID \
            FAKE_CURL_REDIRECT_STATUS \
            FAKE_CURL_REDIRECT_URL \
            FAKE_DIRNAME_OUTPUT \
            RUNNER_TEMP \
            STAGING_SERVICE_HEALTH_URLS_JSON \
            STAGING_DEPLOY_WEBHOOK_URL
        while [[ $# -gt 0 && "$1" == *=* ]]; do
            declare -x "$1"
            shift
        done
        if [[ "${LIFEOS_OPERATIONAL_TEST_NO_NATIVE_K6:-false}" == "true" ]]; then
            export PATH="${root}/bin:/usr/bin:/bin"
        fi
        bash "${root}/scripts/${script}" "$@"
    ) > "${RUN_OUTPUT}" 2>&1; then
        RUN_STATUS=0
    else
        RUN_STATUS=$?
    fi
}

test_build_rejects_missing_services() {
    new_harness build-no-services build-container-images.sh

    run_target "${TEST_ROOT}" build-container-images.sh

    assert_status 66 "container build without Dockerfiles"
    assert_file_contains "${RUN_OUTPUT}" "No service Dockerfiles found" "container build without Dockerfiles"
    if [[ -s "${TEST_ROOT}/commands.log" ]]; then
        fail "container build without Dockerfiles must not invoke Docker"
    fi
}

test_build_rejects_missing_or_ambiguous_jars() {
    new_harness build-missing-jar build-container-images.sh
    add_service_dockerfile "${TEST_ROOT}" example-service

    run_target "${TEST_ROOT}" build-container-images.sh

    assert_status 66 "container build without a service jar"
    assert_file_contains "${RUN_OUTPUT}" "Expected exactly one non-plain executable jar" "container build without a service jar"
    if [[ -s "${TEST_ROOT}/commands.log" ]]; then
        fail "container build without a service jar must not invoke Docker"
    fi

    new_harness build-ambiguous-jar build-container-images.sh
    add_service_dockerfile "${TEST_ROOT}" example-service
    add_service_jar "${TEST_ROOT}" example-service example-service-a.jar
    add_service_jar "${TEST_ROOT}" example-service example-service-b.jar
    add_service_jar "${TEST_ROOT}" example-service example-service-plain.jar

    run_target "${TEST_ROOT}" build-container-images.sh

    assert_status 66 "container build with ambiguous jars"
    assert_file_contains "${RUN_OUTPUT}" "Expected exactly one non-plain executable jar" "container build with ambiguous jars"
    if [[ -s "${TEST_ROOT}/commands.log" ]]; then
        fail "container build with ambiguous jars must not invoke Docker"
    fi
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
        if [[ -s "${TEST_ROOT}/commands.log" ]]; then
            fail "container build with invalid reference ${expected_reference} must not invoke Docker"
        fi

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
        if [[ -s "${TEST_ROOT}/commands.log" ]]; then
            fail "container scan with invalid reference ${expected_reference} must not invoke Docker"
        fi
    done
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
        $'lifeos_identity\')\n\\gexec' \
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
    assert_log_contains "${TEST_ROOT}" $'docker\tcompose\tversion' \
        "database provisioning Compose plugin preflight command"
    assert_log_excludes "${TEST_ROOT}" $'\tup\t--detach\t--wait' \
        "database provisioning after a missing Compose plugin"
    assert_log_excludes "${TEST_ROOT}" $'\texec\t-T\tpostgres' \
        "database provisioning SQL execution after a missing Compose plugin"
}

test_database_provisioning_rejects_unbounded_timeout() {
    new_harness provision-invalid-timeout provision-local-databases.sh
    add_database_provisioning_sql "${TEST_ROOT}"

    run_target "${TEST_ROOT}" provision-local-databases.sh LIFEOS_DATABASE_PROVISION_TIMEOUT_SECONDS=301

    assert_status 64 "database provisioning with an out-of-range timeout"
    assert_file_contains "${RUN_OUTPUT}" "must be between 1 and 300 seconds" "database provisioning timeout validation"
    if [[ -s "${TEST_ROOT}/commands.log" ]]; then
        fail "database provisioning with an invalid timeout must not invoke Docker"
    fi

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

test_verifier_repository_root_resolution_fails_closed() {
    local verifier
    for verifier in verify-pipeline-scripts.sh verify-sbom.sh; do
        new_harness "${verifier%.sh}-root-resolution" "${verifier}"
        ln -s "${TEST_SCRIPT_PATH}" "${TEST_ROOT}/bin/dirname"

        run_target "${TEST_ROOT}" "${verifier}"

        assert_nonzero_status "${verifier} with an unresolvable repository root"
        assert_log_contains "${TEST_ROOT}" $'dirname\t' \
            "${verifier} repository-root lookup"
        assert_file_excludes "${RUN_OUTPUT}" "Validated" \
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

    run_target "${TEST_ROOT}" performance-smoke-test.sh \
        "LIFEOS_OPERATIONAL_TEST_NO_NATIVE_K6=true" \
        "LIFEOS_PERFORMANCE_GATEWAY_MANAGEMENT_BASE_URL=https://gateway.example.test" \
        "LIFEOS_PERFORMANCE_SUMMARY_PATH=build/reports/performance/k6-summary.json"

    assert_status 0 "performance smoke test using Docker fallback"
    assert_log_contains "${TEST_ROOT}" \
        $'docker\trun\t--rm\t--volume\t'"${TEST_ROOT}"$':/work:ro\t--volume\t' \
        "performance Docker fallback read-only repository mount"
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
    mkdir -p "${TEST_ROOT}/prerequisite-bin"
    ln -s "$(command -p -v bash)" "${TEST_ROOT}/prerequisite-bin/bash"
    ln -s "$(command -p -v dirname)" "${TEST_ROOT}/prerequisite-bin/dirname"

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
        $'curl\t--fail\t--silent\t--show-error\t--location\t--max-redirs\t0\t--proto\t=https\t--connect-timeout\t10\t--max-time\t120' \
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
        $'curl\t--fail\t--silent\t--show-error\t--location\t--max-redirs\t0\t--proto\t=https' \
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
        $'curl\t--silent\t--show-error\t--location\t--max-redirs\t0\t--proto\t=https\t--connect-timeout\t10\t--max-time\t20\t--header\tX-Correlation-ID: 11111111-1111-4111-8111-111111111111' \
        "end-to-end account redirect rejection"
    assert_file_excludes "${RUN_OUTPUT}" \
        "End-to-end gateway-to-identity contract passed" \
        "end-to-end smoke after an intermediate correlation header"
    assert_log_contains "${TEST_ROOT}" \
        $'curl\t--fail\t--silent\t--show-error\t--location\t--proto\t=https\t--connect-timeout\t10\t--max-time\t20\t--retry\t5\t--retry-all-errors\thttps://gateway-management.example.test/actuator/health/readiness' \
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
        $'curl\t--fail\t--silent\t--show-error\t--location\t--max-redirs\t0\t--proto\t=https\t--connect-timeout\t10\t--max-time\t300' \
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

    new_harness staging-readiness-failure staging-smoke-test.sh
    add_service_dockerfile "${TEST_ROOT}" example-service

    run_target "${TEST_ROOT}" staging-smoke-test.sh \
        'STAGING_SERVICE_HEALTH_URLS_JSON={"example-service":"https://staging.example.test/actuator/health/readiness"}' \
        "FAKE_JQ_SERVICE_URL=https://staging.example.test/actuator/health/readiness" \
        "FAKE_JQ_READINESS_STATUS=1"

    assert_nonzero_status "staging smoke readiness failure"
    assert_log_contains "${TEST_ROOT}" \
        $'curl\t--fail\t--silent\t--show-error\t--location\t--proto\t=https\t--connect-timeout\t10\t--max-time\t20\t--retry\t5\t--retry-all-errors\thttps://staging.example.test/actuator/health/readiness' \
        "staging smoke readiness transport"

    new_harness end-to-end-readiness-failure end-to-end-smoke-test.sh

    # The account-registration request is live-topology-only because its behavior belongs to the
    # deployed Gateway -> Identity route. This deterministic case proves a failed prerequisite
    # short-circuits before that request can run.
    run_target "${TEST_ROOT}" end-to-end-smoke-test.sh \
        "LIFEOS_E2E_GATEWAY_BASE_URL=https://gateway.example.test" \
        "LIFEOS_E2E_GATEWAY_MANAGEMENT_BASE_URL=https://gateway-management.example.test" \
        "LIFEOS_E2E_IDENTITY_MANAGEMENT_BASE_URL=https://identity-management.example.test" \
        "FAKE_JQ_READINESS_STATUS=1"

    assert_nonzero_status "end-to-end smoke readiness failure"
    assert_log_line_count "${TEST_ROOT}" \
        $'curl\t--fail\t--silent\t--show-error\t--location\t--proto\t=https\t--connect-timeout\t10\t--max-time\t20\t--retry\t5\t--retry-all-errors\thttps://gateway-management.example.test/actuator/health/readiness' \
        1 \
        "end-to-end smoke prerequisite short circuit"
    assert_log_excludes "${TEST_ROOT}" $'/api/v1/accounts' "end-to-end smoke after a failed prerequisite"
}

test_chaos_experiment_uses_bounded_payload_transport_and_recovery_probes() {
    new_harness chaos-experiment run-chaos-experiment.sh

    run_target "${TEST_ROOT}" run-chaos-experiment.sh \
        "LIFEOS_CHAOS_EXPERIMENT_WEBHOOK_URL=https://chaos.example.test/experiments" \
        "LIFEOS_CHAOS_GATEWAY_HEALTH_URL=https://gateway-management.example.test/actuator/health/readiness" \
        "LIFEOS_CHAOS_IDENTITY_HEALTH_URL=https://identity-management.example.test/actuator/health/readiness" \
        "LIFEOS_CHAOS_TASK_GOAL_HEALTH_URL=https://task-goal.example.test/actuator/health" \
        "GITHUB_RUN_ID=run-42"

    assert_status 0 "chaos experiment with successful recovery probes"
    assert_log_contains "${TEST_ROOT}" \
        $'curl\t--fail\t--silent\t--show-error\t--location\t--max-redirs\t0\t--proto\t=https\t--connect-timeout\t10\t--max-time\t300' \
        "chaos experiment bounded webhook transport"
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
    assert_log_line_count "${TEST_ROOT}" $'/actuator/health/readiness' 3 \
        "chaos experiment recovery readiness probes"
}

test_build_rejects_missing_services
test_build_rejects_missing_or_ambiguous_jars
test_build_passes_jar_argument_and_honors_push_switch
test_container_scripts_reject_invalid_generated_image_references
test_container_scan_rejects_missing_images_and_passes_trivy_arguments
test_container_scan_requires_an_accessible_docker_daemon
test_source_scan_uses_read_only_repository_mount_and_filesystem_arguments
test_database_provisioning_waits_before_exec_and_handles_failures
test_database_provisioning_requires_the_compose_plugin
test_database_provisioning_rejects_unbounded_timeout
test_database_provisioning_sql_keeps_create_queries_open_for_gexec
test_verifier_repository_root_resolution_fails_closed
test_performance_smoke_accepts_100_vus_and_prefers_k6
test_performance_smoke_docker_fallback_uses_read_only_repository_mount
test_performance_smoke_rejects_escaped_summary_paths
test_performance_smoke_rejects_invalid_vus_values
test_deploy_staging_rejects_unsafe_webhooks_and_uses_bounded_transport
test_contract_sensitive_posts_reject_redirects
test_staging_and_end_to_end_smoke_fail_closed_before_live_traffic
test_chaos_experiment_uses_bounded_payload_transport_and_recovery_probes

printf '%s\n' 'Operational script behavioral tests passed'
