#!/usr/bin/env bash
set -euo pipefail

REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly REPOSITORY_ROOT
readonly PROVISION_FILE="${REPOSITORY_ROOT}/infrastructure/docker-compose/provision-databases.sql"
# Official PostgreSQL 17.11 Alpine multi-platform manifest from Docker Hub, reviewed 2026-08-25.
# Update this intentionally after reviewing the replacement manifest and its supported platforms.
readonly DEFAULT_POSTGRES_IMAGE="postgres:17-alpine@sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73"
readonly POSTGRES_IMAGE="${LIFEOS_PROVISION_CONCURRENCY_POSTGRES_IMAGE:-${DEFAULT_POSTGRES_IMAGE}}"
readonly POSTGRES_USER="lifeos_provision_test"
POSTGRES_PASSWORD="${LIFEOS_PROVISION_CONCURRENCY_POSTGRES_PASSWORD:-}"
if [[ -z "${POSTGRES_PASSWORD}" ]]; then
    # Each disposable container receives an unpredictable test-only credential; it is never logged.
    POSTGRES_PASSWORD="$(LC_ALL=C od -An -N16 -tx1 /dev/urandom | tr -d '[:space:]')"
fi
readonly POSTGRES_PASSWORD
CONTAINER_NAME="lifeos-provision-concurrency-$$-$(date +%s)"
readonly CONTAINER_NAME
readonly LOCK_NAME="lifeos.provision-databases"
readonly LOCK_HOLDER_APPLICATION_NAME="lifeos-provision-lock-holder"
readonly WORKER_APPLICATION_NAME="lifeos-provision-concurrency-worker"
# Keep the lock observation below the provisioning SQL's 45-second lock timeout, even when
# individual Docker/psql probes are delayed by a loaded runner.
readonly MAXIMUM_OBSERVATION_SECONDS=30
readonly POLL_INTERVAL_SECONDS=0.1
readonly OBSERVATION_TIMEOUT_EXIT_STATUS=124
# GNU timeout exits 128 + SIGKILL when its child is killed after the deadline.
readonly OBSERVATION_TIMEOUT_SIGNAL_EXIT_STATUS=137
# Preserve bounded failure diagnostics and container cleanup without consuming the provisioning
# SQL's remaining 15-second lock-timeout headroom after a 30-second observation window.
readonly FAILURE_RECOVERY_TIMEOUT_SECONDS=3
# Image pulls can be slow on a cold CI runner, but must still be bounded independently of the
# 30-second container-start observation window below.
readonly IMAGE_PULL_TIMEOUT_SECONDS=120

TEST_DIRECTORY=""
container_started=false
lock_holder_pid=""
first_worker_pid=""
second_worker_pid=""

cleanup() {
    local status=$?
    local process_id

    for process_id in "${lock_holder_pid}" "${first_worker_pid}" "${second_worker_pid}"; do
        if [[ -n "${process_id}" ]] && kill -0 "${process_id}" >/dev/null 2>&1; then
            kill "${process_id}" >/dev/null 2>&1 || true
        fi
    done

    if [[ "${container_started}" == true ]]; then
        local cleanup_deadline_seconds=$(( SECONDS + FAILURE_RECOVERY_TIMEOUT_SECONDS ))
        run_docker_with_deadline "${cleanup_deadline_seconds}" rm --force "${CONTAINER_NAME}" \
            >/dev/null 2>&1 || true
    fi

    if [[ -n "${TEST_DIRECTORY}" ]]; then
        rm -rf -- "${TEST_DIRECTORY}"
    fi

    exit "${status}"
}

fail() {
    printf 'FAIL: %s\n' "$*" >&2
    exit 1
}

verify_session_lock_structure() {
    local validation_error

    if validation_error="$(awk '
        {
            code = $0
            sub(/--.*/, "", code)

            if (code ~ /pg_advisory_xact_lock(_shared)?[[:space:]]*\(/) {
                transaction_lock_line = NR
            }
            if (code ~ /pg_advisory_lock[[:space:]]*\(/ && session_lock_line == 0) {
                session_lock_line = NR
            }
            if (code ~ /pg_advisory_unlock[[:space:]]*\(/ && session_unlock_line == 0) {
                session_unlock_line = NR
            }
            if (code ~ /^[[:space:]]*\\gexec[[:space:]]*$/) {
                gexec_count++
                if (gexec_count == 1) {
                    first_gexec_line = NR
                }
                if (gexec_count == 2) {
                    second_gexec_line = NR
                }
            }
        }
        END {
            if (transaction_lock_line > 0) {
                printf "transaction-scoped advisory locks are not valid for separate \\gexec transactions (line %d)\n", transaction_lock_line
                exit 1
            }
            if (gexec_count != 2) {
                printf "expected exactly two \\gexec operations, found %d\n", gexec_count
                exit 1
            }
            if (session_lock_line == 0) {
                print "missing session-level pg_advisory_lock"
                exit 1
            }
            if (session_lock_line >= first_gexec_line) {
                printf "pg_advisory_lock must precede the first \\gexec (lock line %d, first \\gexec line %d)\n", session_lock_line, first_gexec_line
                exit 1
            }
            if (session_unlock_line == 0) {
                print "missing session-level pg_advisory_unlock"
                exit 1
            }
            if (session_unlock_line <= second_gexec_line) {
                printf "pg_advisory_unlock must follow the second \\gexec (unlock line %d, second \\gexec line %d)\n", session_unlock_line, second_gexec_line
                exit 1
            }
        }
    ' "${PROVISION_FILE}")"; then
        return 0
    fi

    printf 'Invalid database provisioning advisory-lock structure: %s\n' "${validation_error}" >&2
    return 1
}

if [[ ! -r "${PROVISION_FILE}" ]]; then
    echo "database provisioning SQL is missing: ${PROVISION_FILE}" >&2
    exit 66
fi

if ! verify_session_lock_structure; then
    exit 65
fi

if ! command -v docker >/dev/null 2>&1; then
    echo "docker is required to run the concurrent database provisioning regression test" >&2
    exit 69
fi

if command -v timeout >/dev/null 2>&1; then
    OBSERVATION_TIMEOUT_COMMAND="timeout"
elif command -v gtimeout >/dev/null 2>&1; then
    # macOS ships no timeout utility; Homebrew's coreutils exposes the GNU-compatible command
    # as gtimeout. Prefer timeout on CI/Linux while retaining a clear local development path.
    OBSERVATION_TIMEOUT_COMMAND="gtimeout"
else
    echo "timeout (or gtimeout on macOS) is required to bound PostgreSQL readiness observations" >&2
    exit 69
fi
readonly OBSERVATION_TIMEOUT_COMMAND

if ! docker info >/dev/null 2>&1; then
    echo "a running Docker daemon is required to run the concurrent database provisioning regression test" >&2
    exit 69
fi

TEST_DIRECTORY="$(mktemp -d "${TMPDIR:-/tmp}/lifeos-provision-concurrency.XXXXXX")"
trap cleanup EXIT
lock_holder_status_file="${TEST_DIRECTORY}/lock-holder.status"
first_worker_status_file="${TEST_DIRECTORY}/first-worker.status"
second_worker_status_file="${TEST_DIRECTORY}/second-worker.status"

remaining_observation_timeout_seconds() {
    local deadline_seconds="$1"
    local remaining_seconds=$(( deadline_seconds - SECONDS ))

    # SECONDS has whole-second granularity. Reserve a full second before each external probe so
    # timeout's duration, command-start overhead, and a hard kill cannot cross the overall
    # observation deadline.
    if (( remaining_seconds <= 1 )); then
        return 1
    fi

    printf '%s\n' "$(( remaining_seconds - 1 ))"
}

run_docker_with_deadline() {
    local deadline_seconds="$1"
    local timeout_seconds

    shift
    if ! timeout_seconds="$(remaining_observation_timeout_seconds "${deadline_seconds}")"; then
        return "${OBSERVATION_TIMEOUT_EXIT_STATUS}"
    fi

    # KILL avoids a grace-period overrun after a probe consumes its remaining budget. The
    # disposable container is removed by cleanup, so a killed Docker client cannot leak it.
    "${OBSERVATION_TIMEOUT_COMMAND}" --signal=KILL "${timeout_seconds}s" docker "$@"
}

is_observation_timeout_status() {
    local command_exit_status="$1"

    [[ "${command_exit_status}" -eq "${OBSERVATION_TIMEOUT_EXIT_STATUS}" \
        || "${command_exit_status}" -eq "${OBSERVATION_TIMEOUT_SIGNAL_EXIT_STATUS}" ]]
}

postgres_query_before_deadline() {
    local deadline_seconds="$1"
    local query="$2"

    run_docker_with_deadline "${deadline_seconds}" exec "${CONTAINER_NAME}" \
        psql --username "${POSTGRES_USER}" --dbname postgres --set ON_ERROR_STOP=1 \
        --tuples-only --no-align --quiet --command "${query}"
}

record_command_status() {
    local status_file="$1"
    local command_exit_status="$2"

    printf '%s\n' "${command_exit_status}" >"${status_file}.tmp"
    mv "${status_file}.tmp" "${status_file}"
}

run_lock_holder() {
    local status_file="$1"
    local command_exit_status

    # Set this in PostgreSQL itself so pg_stat_activity is deterministic across Docker and libpq
    # versions; relying only on the client-side PGAPPNAME environment variable was CI-dependent.
    if docker exec "${CONTAINER_NAME}" \
        psql --username "${POSTGRES_USER}" --dbname postgres --set ON_ERROR_STOP=1 \
        --command "SET application_name = '${LOCK_HOLDER_APPLICATION_NAME}'; SELECT pg_advisory_lock(hashtextextended('${LOCK_NAME}', 0)); SELECT pg_sleep(60);"; then
        record_command_status "${status_file}" 0
        return 0
    else
        command_exit_status=$?
        record_command_status "${status_file}" "${command_exit_status}"
        return "${command_exit_status}"
    fi
}

run_provisioning_worker() {
    local status_file="$1"
    local command_exit_status

    # Keep the tested provisioning SQL intact while explicitly setting the server-side activity
    # name in the same psql session used for its advisory lock and both CREATE DATABASE commands.
    if {
        printf "SET application_name = '%s';\n" "${WORKER_APPLICATION_NAME}"
        sed -n '1,$p' "${PROVISION_FILE}"
    } | docker exec --interactive "${CONTAINER_NAME}" \
        psql --username "${POSTGRES_USER}" --dbname postgres --set ON_ERROR_STOP=1; then
        record_command_status "${status_file}" 0
        return 0
    else
        command_exit_status=$?
        record_command_status "${status_file}" "${command_exit_status}"
        return "${command_exit_status}"
    fi
}

print_background_log() {
    local description="$1"
    local log_file="$2"

    printf 'Output from %s:\n' "${description}" >&2
    if [[ -s "${log_file}" ]]; then
        sed -n '1,160p' "${log_file}" >&2
    else
        echo "(no output captured)" >&2
    fi
}

print_container_diagnostics() {
    local diagnostic_deadline_seconds=$(( SECONDS + FAILURE_RECOVERY_TIMEOUT_SECONDS ))

    if [[ "${container_started}" != true ]]; then
        return 0
    fi

    echo "PostgreSQL container state:" >&2
    if ! run_docker_with_deadline "${diagnostic_deadline_seconds}" inspect \
        --format 'status={{.State.Status}} exit-code={{.State.ExitCode}}' "${CONTAINER_NAME}" >&2; then
        echo "(container state unavailable within bounded diagnostic budget)" >&2
    fi
    echo "PostgreSQL container logs:" >&2
    if ! run_docker_with_deadline "${diagnostic_deadline_seconds}" logs --tail 160 "${CONTAINER_NAME}" >&2; then
        echo "(container logs unavailable within bounded diagnostic budget)" >&2
    fi
}

final_postgres_is_ready() {
    local deadline_seconds="$1"
    local result

    # The official image briefly runs a bootstrap postmaster while initdb completes, then replaces
    # it with the long-lived PID 1 server. A successful query alone can hit that transient server.
    # shellcheck disable=SC2016 # The container shell, not this script, expands process_name.
    if ! run_docker_with_deadline "${deadline_seconds}" exec "${CONTAINER_NAME}" sh -ec \
        'read -r process_name < /proc/1/comm; test "${process_name}" = postgres' >/dev/null 2>&1; then
        return 1
    fi

    if result="$(postgres_query_before_deadline "${deadline_seconds}" "SELECT 1;" 2>/dev/null)" \
        && [[ "${result//[[:space:]]/}" == "1" ]]; then
        return 0
    fi

    return 1
}

wait_for_final_postgres() {
    local deadline_seconds=$(( SECONDS + MAXIMUM_OBSERVATION_SECONDS ))

    while (( SECONDS < deadline_seconds )); do
        if final_postgres_is_ready "${deadline_seconds}"; then
            return 0
        fi
        sleep "${POLL_INTERVAL_SECONDS}"
    done

    print_container_diagnostics
    fail "timed out waiting for the final PostgreSQL server process"
}

fail_if_background_process_finished() {
    local process_id="$1"
    local status_file="$2"
    local log_file="$3"
    local description="$4"
    local command_exit_status

    if [[ -f "${status_file}" ]]; then
        if ! IFS= read -r command_exit_status <"${status_file}"; then
            command_exit_status="unknown"
        fi
        print_background_log "${description}" "${log_file}"
        print_container_diagnostics
        if [[ "${command_exit_status}" == "0" ]]; then
            fail "${description} completed before reaching its expected state"
        fi
        fail "${description} exited with status ${command_exit_status} before reaching its expected state"
    fi

    if ! kill -0 "${process_id}" >/dev/null 2>&1; then
        wait "${process_id}" || true
        print_background_log "${description}" "${log_file}"
        print_container_diagnostics
        fail "${description} exited before recording its status"
    fi
}

wait_for_background_query_result() {
    local description="$1"
    local query="$2"
    local expected_result="$3"
    local result
    local deadline_seconds

    shift 3
    if (( $# == 0 || $# % 4 != 0 )); then
        fail "background query wait for ${description} requires process, status, log, and description details"
    fi

    deadline_seconds=$(( SECONDS + MAXIMUM_OBSERVATION_SECONDS ))
    while (( SECONDS < deadline_seconds )); do
        local details=("$@")
        local index
        for ((index = 0; index < ${#details[@]}; index += 4)); do
            fail_if_background_process_finished \
                "${details[index]}" "${details[index + 1]}" "${details[index + 2]}" "${details[index + 3]}"
        done

        if result="$(postgres_query_before_deadline "${deadline_seconds}" "${query}" 2>/dev/null)" \
            && [[ "${result//[[:space:]]/}" == "${expected_result}" ]]; then
            return 0
        fi
        sleep "${POLL_INTERVAL_SECONDS}"
    done

    local details=("$@")
    local index
    for ((index = 0; index < ${#details[@]}; index += 4)); do
        fail_if_background_process_finished \
            "${details[index]}" "${details[index + 1]}" "${details[index + 2]}" "${details[index + 3]}"
        print_background_log "${details[index + 3]}" "${details[index + 2]}"
    done
    print_container_diagnostics
    fail "timed out waiting for ${description}"
}

wait_for_process() {
    local process_id="$1"
    local status_file="$2"
    local log_file="$3"
    local description="$4"
    local deadline_seconds=$(( SECONDS + MAXIMUM_OBSERVATION_SECONDS ))

    # A background psql client can outlive the lock-holder failure path. Observe its atomic status
    # marker until the same wall-clock deadline used by readiness polling, then terminate it before
    # collecting diagnostics. The status marker is written immediately before the worker returns,
    # so a present marker makes the final wait a bounded reap of an already-completing process.
    while [[ ! -f "${status_file}" ]] && kill -0 "${process_id}" >/dev/null 2>&1; do
        if (( SECONDS >= deadline_seconds )); then
            printf 'Timed out waiting for %s; terminating the background process\n' "${description}" >&2
            kill "${process_id}" >/dev/null 2>&1 || true
            kill -KILL "${process_id}" >/dev/null 2>&1 || true
            wait "${process_id}" >/dev/null 2>&1 || true
            print_background_log "${description}" "${log_file}"
            print_container_diagnostics
            fail "timed out waiting for ${description}"
        fi
        sleep "${POLL_INTERVAL_SECONDS}"
    done

    if [[ ! -f "${status_file}" ]] && ! kill -0 "${process_id}" >/dev/null 2>&1; then
        wait "${process_id}" >/dev/null 2>&1 || true
        print_background_log "${description}" "${log_file}"
        print_container_diagnostics
        fail "${description} exited before recording its status"
    fi

    if ! wait "${process_id}"; then
        printf 'Output from failed %s:\n' "${description}" >&2
        sed -n '1,160p' "${log_file}" >&2 || true
        fail "${description} failed"
    fi
}

wait_for_lock_holder_termination() {
    local process_id="$1"
    local status_file="$2"
    local log_file="$3"
    local description="$4"
    local deadline_seconds=$(( SECONDS + MAXIMUM_OBSERVATION_SECONDS ))
    local command_exit_status

    # A terminated PostgreSQL backend should release the session advisory lock, but the client
    # process can still be stuck if Docker does not tear down its exec session. Bound that reap
    # separately so this regression never hangs after the holder termination request.
    while [[ ! -f "${status_file}" ]] && kill -0 "${process_id}" >/dev/null 2>&1; do
        if (( SECONDS >= deadline_seconds )); then
            printf 'Timed out waiting for %s to terminate; terminating the background process\n' \
                "${description}" >&2
            kill "${process_id}" >/dev/null 2>&1 || true
            kill -KILL "${process_id}" >/dev/null 2>&1 || true
            wait "${process_id}" >/dev/null 2>&1 || true
            print_background_log "${description}" "${log_file}"
            print_container_diagnostics
            fail "timed out waiting for ${description} to terminate"
        fi
        sleep "${POLL_INTERVAL_SECONDS}"
    done

    if [[ ! -f "${status_file}" ]]; then
        wait "${process_id}" >/dev/null 2>&1 || true
        print_background_log "${description}" "${log_file}"
        print_container_diagnostics
        fail "${description} exited before recording its status"
    fi

    if ! IFS= read -r command_exit_status <"${status_file}"; then
        command_exit_status="unknown"
    fi
    if [[ "${command_exit_status}" == "0" ]]; then
        fail "${description} unexpectedly completed without termination"
    fi

    # The status marker is written immediately before the worker returns; this is now a bounded
    # reap of a process that has already completed its Docker command.
    wait "${process_id}" >/dev/null 2>&1 || true
}

image_pull_deadline_seconds=$(( SECONDS + IMAGE_PULL_TIMEOUT_SECONDS ))
if run_docker_with_deadline "${image_pull_deadline_seconds}" pull "${POSTGRES_IMAGE}" >/dev/null; then
    :
else
    command_exit_status=$?
    if is_observation_timeout_status "${command_exit_status}"; then
        printf 'Timed out pulling the PostgreSQL image within the bounded image-pull window\n' >&2
        exit 69
    fi
    printf 'Could not pull the PostgreSQL image before starting the container\n' >&2
    exit "${command_exit_status}"
fi

container_started=true
startup_deadline_seconds=$(( SECONDS + MAXIMUM_OBSERVATION_SECONDS ))
if run_docker_with_deadline "${startup_deadline_seconds}" run --detach --rm --name "${CONTAINER_NAME}" \
    --env "POSTGRES_USER=${POSTGRES_USER}" \
    --env "POSTGRES_PASSWORD=${POSTGRES_PASSWORD}" \
    --env "POSTGRES_DB=postgres" \
    "${POSTGRES_IMAGE}" >/dev/null; then
    :
else
    command_exit_status=$?
    if is_observation_timeout_status "${command_exit_status}"; then
        printf 'Timed out starting the PostgreSQL container within the bounded observation window\n' >&2
        exit 69
    fi
    exit "${command_exit_status}"
fi

wait_for_final_postgres

# Queue both workers on the same lock. This makes the concurrency assertion deterministic instead of
# relying on an incidental CREATE DATABASE race to overlap. The 60-second holder is terminated after
# a bounded 30-second observation window, before the source SQL's 45-second lock timeout can expire.
run_lock_holder "${lock_holder_status_file}" >"${TEST_DIRECTORY}/lock-holder.log" 2>&1 &
lock_holder_pid=$!

wait_for_background_query_result \
    "the advisory lock holder" \
    "SELECT count(*) FROM pg_locks AS locks JOIN pg_stat_activity AS activity USING (pid) WHERE activity.application_name = '${LOCK_HOLDER_APPLICATION_NAME}' AND locks.locktype = 'advisory' AND locks.granted;" \
    "1" \
    "${lock_holder_pid}" "${lock_holder_status_file}" "${TEST_DIRECTORY}/lock-holder.log" "the advisory lock holder"

run_provisioning_worker "${first_worker_status_file}" >"${TEST_DIRECTORY}/first-worker.log" 2>&1 &
first_worker_pid=$!

run_provisioning_worker "${second_worker_status_file}" >"${TEST_DIRECTORY}/second-worker.log" 2>&1 &
second_worker_pid=$!

wait_for_background_query_result \
    "both provisioning workers to wait on the advisory lock" \
    "SELECT count(*) FROM pg_stat_activity WHERE application_name = '${WORKER_APPLICATION_NAME}' AND wait_event_type = 'Lock' AND wait_event = 'advisory';" \
    "2" \
    "${first_worker_pid}" "${first_worker_status_file}" "${TEST_DIRECTORY}/first-worker.log" "the first provisioning worker" \
    "${second_worker_pid}" "${second_worker_status_file}" "${TEST_DIRECTORY}/second-worker.log" "the second provisioning worker"

foreground_deadline_seconds=$(( SECONDS + MAXIMUM_OBSERVATION_SECONDS ))
if terminated_lock_holders="$(postgres_query_before_deadline "${foreground_deadline_seconds}" \
    "SELECT count(*) FROM (SELECT pg_terminate_backend(pid) AS terminated FROM pg_stat_activity WHERE application_name = '${LOCK_HOLDER_APPLICATION_NAME}') AS terminated WHERE terminated;")"; then
    :
else
    command_exit_status=$?
    if is_observation_timeout_status "${command_exit_status}"; then
        fail "timed out terminating the advisory lock holder within the bounded observation window"
    fi
    fail "could not terminate the advisory lock holder"
fi
if [[ "${terminated_lock_holders//[[:space:]]/}" != "1" ]]; then
    fail "expected to terminate one advisory lock holder, got ${terminated_lock_holders}"
fi

wait_for_lock_holder_termination "${lock_holder_pid}" "${lock_holder_status_file}" \
    "${TEST_DIRECTORY}/lock-holder.log" "the advisory lock holder"
lock_holder_pid=""

wait_for_process "${first_worker_pid}" "${first_worker_status_file}" \
    "${TEST_DIRECTORY}/first-worker.log" "first provisioning worker"
first_worker_pid=""
wait_for_process "${second_worker_pid}" "${second_worker_status_file}" \
    "${TEST_DIRECTORY}/second-worker.log" "second provisioning worker"
second_worker_pid=""

# The lock-holder wait above can consume most of the observation window. Start a fresh bounded
# window for the final database inventory so a successful termination is not reported as a query
# timeout merely because the earlier wait used the original deadline.
foreground_deadline_seconds=$(( SECONDS + MAXIMUM_OBSERVATION_SECONDS ))
if created_databases="$(postgres_query_before_deadline "${foreground_deadline_seconds}" \
    "SELECT datname FROM pg_database WHERE datname IN ('lifeos_identity', 'lifeos_task_goal') ORDER BY datname;")"; then
    :
else
    command_exit_status=$?
    if is_observation_timeout_status "${command_exit_status}"; then
        fail "timed out querying created databases within the bounded observation window"
    fi
    fail "could not query created databases"
fi
if [[ "${created_databases}" != $'lifeos_identity\nlifeos_task_goal' ]]; then
    fail "concurrent provisioning did not create both databases: ${created_databases}"
fi

echo "Concurrent database provisioning regression test passed"
