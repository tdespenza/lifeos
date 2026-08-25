#!/usr/bin/env bash
set -euo pipefail

REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly REPOSITORY_ROOT
readonly PROVISION_FILE="${REPOSITORY_ROOT}/infrastructure/docker-compose/provision-databases.sql"
readonly POSTGRES_IMAGE="${LIFEOS_PROVISION_CONCURRENCY_POSTGRES_IMAGE:-postgres:17-alpine}"
readonly POSTGRES_USER="lifeos_provision_test"
readonly POSTGRES_PASSWORD="lifeos_provision_test_password"
CONTAINER_NAME="lifeos-provision-concurrency-$$-$(date +%s)"
readonly CONTAINER_NAME
readonly LOCK_NAME="lifeos.provision-databases"
readonly LOCK_HOLDER_APPLICATION_NAME="lifeos-provision-lock-holder"
readonly WORKER_APPLICATION_NAME="lifeos-provision-concurrency-worker"
readonly MAXIMUM_POLL_ATTEMPTS=300
readonly POLL_INTERVAL_SECONDS=0.1

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
        docker rm --force "${CONTAINER_NAME}" >/dev/null 2>&1 || true
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
                printf "transaction-scoped advisory locks are not valid for separate \\gexec transactions (line %d)\\n", transaction_lock_line
                exit 1
            }
            if (gexec_count != 2) {
                printf "expected exactly two \\gexec operations, found %d\\n", gexec_count
                exit 1
            }
            if (session_lock_line == 0) {
                print "missing session-level pg_advisory_lock"
                exit 1
            }
            if (session_lock_line >= first_gexec_line) {
                printf "pg_advisory_lock must precede the first \\gexec (lock line %d, first \\gexec line %d)\\n", session_lock_line, first_gexec_line
                exit 1
            }
            if (session_unlock_line == 0) {
                print "missing session-level pg_advisory_unlock"
                exit 1
            }
            if (session_unlock_line <= second_gexec_line) {
                printf "pg_advisory_unlock must follow the second \\gexec (unlock line %d, second \\gexec line %d)\\n", session_unlock_line, second_gexec_line
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

if ! docker info >/dev/null 2>&1; then
    echo "a running Docker daemon is required to run the concurrent database provisioning regression test" >&2
    exit 69
fi

TEST_DIRECTORY="$(mktemp -d "${TMPDIR:-/tmp}/lifeos-provision-concurrency.XXXXXX")"
trap cleanup EXIT

postgres_query() {
    docker exec "${CONTAINER_NAME}" \
        psql --username "${POSTGRES_USER}" --dbname postgres --set ON_ERROR_STOP=1 \
        --tuples-only --no-align --quiet --command "$1"
}

wait_for_query_result() {
    local description="$1"
    local query="$2"
    local expected_result="$3"
    local result
    local attempt

    for ((attempt = 1; attempt <= MAXIMUM_POLL_ATTEMPTS; attempt++)); do
        if result="$(postgres_query "${query}" 2>/dev/null)" \
            && [[ "${result//[[:space:]]/}" == "${expected_result}" ]]; then
            return 0
        fi
        sleep "${POLL_INTERVAL_SECONDS}"
    done

    fail "timed out waiting for ${description}"
}

wait_for_process() {
    local process_id="$1"
    local log_file="$2"
    local description="$3"

    if ! wait "${process_id}"; then
        printf 'Output from failed %s:\n' "${description}" >&2
        sed -n '1,160p' "${log_file}" >&2 || true
        fail "${description} failed"
    fi
}

docker run --detach --rm --name "${CONTAINER_NAME}" \
    --env "POSTGRES_USER=${POSTGRES_USER}" \
    --env "POSTGRES_PASSWORD=${POSTGRES_PASSWORD}" \
    --env "POSTGRES_DB=postgres" \
    "${POSTGRES_IMAGE}" >/dev/null
container_started=true

wait_for_query_result "PostgreSQL startup" "SELECT 1;" "1"

# Queue both workers on the same lock. This makes the concurrency assertion deterministic instead of
# relying on an incidental CREATE DATABASE race to overlap. The 60-second holder is terminated after
# a bounded 30-second observation window, before the source SQL's 45-second lock timeout can expire.
docker exec --env "PGAPPNAME=${LOCK_HOLDER_APPLICATION_NAME}" "${CONTAINER_NAME}" \
    psql --username "${POSTGRES_USER}" --dbname postgres --set ON_ERROR_STOP=1 \
    --command "SELECT pg_advisory_lock(hashtextextended('${LOCK_NAME}', 0)); SELECT pg_sleep(60);" \
    >"${TEST_DIRECTORY}/lock-holder.log" 2>&1 &
lock_holder_pid=$!

wait_for_query_result \
    "the advisory lock holder" \
    "SELECT count(*) FROM pg_locks AS locks JOIN pg_stat_activity AS activity USING (pid) WHERE activity.application_name = '${LOCK_HOLDER_APPLICATION_NAME}' AND locks.locktype = 'advisory' AND locks.granted;" \
    "1"

docker exec --env "PGAPPNAME=${WORKER_APPLICATION_NAME}" --interactive "${CONTAINER_NAME}" \
    psql --username "${POSTGRES_USER}" --dbname postgres --set ON_ERROR_STOP=1 \
    <"${PROVISION_FILE}" >"${TEST_DIRECTORY}/first-worker.log" 2>&1 &
first_worker_pid=$!

docker exec --env "PGAPPNAME=${WORKER_APPLICATION_NAME}" --interactive "${CONTAINER_NAME}" \
    psql --username "${POSTGRES_USER}" --dbname postgres --set ON_ERROR_STOP=1 \
    <"${PROVISION_FILE}" >"${TEST_DIRECTORY}/second-worker.log" 2>&1 &
second_worker_pid=$!

wait_for_query_result \
    "both provisioning workers to wait on the advisory lock" \
    "SELECT count(*) FROM pg_stat_activity WHERE application_name = '${WORKER_APPLICATION_NAME}' AND wait_event_type = 'Lock' AND wait_event = 'advisory';" \
    "2"

if ! terminated_lock_holders="$(postgres_query \
    "SELECT count(*) FROM (SELECT pg_terminate_backend(pid) AS terminated FROM pg_stat_activity WHERE application_name = '${LOCK_HOLDER_APPLICATION_NAME}') AS terminated WHERE terminated;")"; then
    fail "could not terminate the advisory lock holder"
fi
if [[ "${terminated_lock_holders//[[:space:]]/}" != "1" ]]; then
    fail "expected to terminate one advisory lock holder, got ${terminated_lock_holders}"
fi

if wait "${lock_holder_pid}"; then
    fail "the advisory lock holder unexpectedly completed without termination"
fi
lock_holder_pid=""

wait_for_process "${first_worker_pid}" "${TEST_DIRECTORY}/first-worker.log" "first provisioning worker"
first_worker_pid=""
wait_for_process "${second_worker_pid}" "${TEST_DIRECTORY}/second-worker.log" "second provisioning worker"
second_worker_pid=""

created_databases="$(postgres_query "SELECT datname FROM pg_database WHERE datname IN ('lifeos_identity', 'lifeos_task_goal') ORDER BY datname;")"
if [[ "${created_databases}" != $'lifeos_identity\nlifeos_task_goal' ]]; then
    fail "concurrent provisioning did not create both databases: ${created_databases}"
fi

echo "Concurrent database provisioning regression test passed"
