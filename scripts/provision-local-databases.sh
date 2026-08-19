#!/usr/bin/env bash
set -euo pipefail

REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly REPOSITORY_ROOT
readonly COMPOSE_FILE="${REPOSITORY_ROOT}/infrastructure/docker-compose/docker-compose.yml"
readonly PROVISION_FILE="${REPOSITORY_ROOT}/infrastructure/docker-compose/provision-databases.sql"

if ! command -v docker >/dev/null 2>&1; then
    echo "docker is required to provision local LifeOS databases" >&2
    exit 69
fi

if [[ ! -f "${PROVISION_FILE}" ]]; then
    echo "Local database provisioning SQL is missing" >&2
    exit 66
fi

# The script intentionally consumes the caller's Compose environment/.env file rather than storing
# credentials. It is idempotent and does not drop, alter, or overwrite existing databases.
docker compose -f "${COMPOSE_FILE}" exec -T postgres \
    sh -ec 'psql --username "$POSTGRES_USER" --dbname postgres --set ON_ERROR_STOP=1' \
    <"${PROVISION_FILE}"
