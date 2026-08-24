#!/usr/bin/env bash
set -euo pipefail

REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly REPOSITORY_ROOT
readonly COMPOSE_FILE="${REPOSITORY_ROOT}/infrastructure/docker-compose/docker-compose.yml"
readonly PROVISION_FILE="${REPOSITORY_ROOT}/infrastructure/docker-compose/provision-databases.sql"
readonly STARTUP_TIMEOUT_SECONDS="${LIFEOS_DATABASE_PROVISION_TIMEOUT_SECONDS:-60}"

if ! command -v docker >/dev/null 2>&1; then
    echo "docker is required to provision local LifeOS databases" >&2
    exit 69
fi

if [[ ! -f "${PROVISION_FILE}" ]]; then
    echo "Local database provisioning SQL is missing" >&2
    exit 66
fi

if [[ ! "${STARTUP_TIMEOUT_SECONDS}" =~ ^[1-9][0-9]{0,2}$ ]] \
    || (( STARTUP_TIMEOUT_SECONDS > 300 )); then
    echo "LIFEOS_DATABASE_PROVISION_TIMEOUT_SECONDS must be between 1 and 300 seconds" >&2
    exit 64
fi

if ! docker compose version >/dev/null 2>&1; then
    echo "docker Compose plugin is required to provision local LifeOS databases" >&2
    exit 69
fi

# The script intentionally consumes the caller's Compose environment/.env file rather than storing
# credentials. It is idempotent and does not drop, alter, or overwrite existing databases.
docker compose -f "${COMPOSE_FILE}" up --detach --wait \
    --wait-timeout "${STARTUP_TIMEOUT_SECONDS}" postgres

docker compose -f "${COMPOSE_FILE}" exec -T postgres \
    sh -ec 'psql --username "$POSTGRES_USER" --dbname postgres --set ON_ERROR_STOP=1' \
    <"${PROVISION_FILE}"
