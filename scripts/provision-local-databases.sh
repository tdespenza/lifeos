#!/usr/bin/env bash
set -euo pipefail

REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly REPOSITORY_ROOT
readonly COMPOSE_FILE="${REPOSITORY_ROOT}/infrastructure/docker-compose/docker-compose.yml"
readonly PROVISION_FILE="${REPOSITORY_ROOT}/infrastructure/docker-compose/provision-databases.sql"
readonly STARTUP_TIMEOUT_SECONDS="${LIFEOS_DATABASE_PROVISION_TIMEOUT_SECONDS:-60}"
readonly MINIMUM_COMPOSE_VERSION_MAJOR=2
readonly MINIMUM_COMPOSE_VERSION_MINOR=17
readonly MINIMUM_COMPOSE_VERSION_PATCH=0

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

if ! compose_version="$(docker compose version --short 2>/dev/null)"; then
    echo "docker Compose plugin is required to provision local LifeOS databases" >&2
    exit 69
fi

# Compose added the bounded --wait-timeout startup flag in 2.17.0. Check the installed plugin
# before issuing `up`, so an older CLI cannot silently ignore or reject the provisioning bound.
if [[ ! "${compose_version}" =~ ^v?([0-9]{1,9})\.([0-9]{1,9})\.([0-9]{1,9})([-+][0-9A-Za-z.-]+)?$ ]]; then
    echo "docker Compose must report a semantic version (for example 2.17.0) to use --wait-timeout" >&2
    exit 69
fi
compose_major=$((10#${BASH_REMATCH[1]}))
compose_minor=$((10#${BASH_REMATCH[2]}))
compose_patch=$((10#${BASH_REMATCH[3]}))

if (( compose_major < MINIMUM_COMPOSE_VERSION_MAJOR
    || (compose_major == MINIMUM_COMPOSE_VERSION_MAJOR
        && (compose_minor < MINIMUM_COMPOSE_VERSION_MINOR
            || (compose_minor == MINIMUM_COMPOSE_VERSION_MINOR
                && compose_patch < MINIMUM_COMPOSE_VERSION_PATCH))) )); then
    echo "docker Compose 2.17.0 or newer is required to use --wait-timeout for local database provisioning" >&2
    exit 69
fi

# The script intentionally consumes the caller's Compose environment/.env file rather than storing
# credentials. It is idempotent and does not drop, alter, or overwrite existing databases.
docker compose -f "${COMPOSE_FILE}" up --detach --wait \
    --wait-timeout "${STARTUP_TIMEOUT_SECONDS}" postgres

docker compose -f "${COMPOSE_FILE}" exec -T postgres \
    sh -ec 'psql --username "$POSTGRES_USER" --dbname postgres --set ON_ERROR_STOP=1' \
    <"${PROVISION_FILE}"
