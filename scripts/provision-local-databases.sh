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
# Docker Desktop and distribution packages append non-SemVer suffixes to the upstream
# Compose version. Keep the upstream numeric triple strict, then classify the suffix so a
# Docker Desktop build is not mistaken for an upstream prerelease such as -rc.1.
readonly COMPOSE_VERSION_PATTERN='^v?([0-9]+)\.([0-9]+)\.([0-9]+)(.*)$'
readonly COMPOSE_VENDOR_BUILD_SUFFIX_PATTERN='^\+([0-9A-Za-z.~_-]+)$'
readonly COMPOSE_DESKTOP_SUFFIX_PATTERN='^-desktop(\.[0-9A-Za-z-]+)*(\+([0-9A-Za-z.~_-]+))?$'
readonly COMPOSE_PRERELEASE_SUFFIX_PATTERN='^-([0-9A-Za-z-]+(\.[0-9A-Za-z-]+)*)(\+([0-9A-Za-z.~_-]+))?$'

is_valid_semver_numeric_identifier() {
    local identifier="$1"

    [[ "${identifier}" == "0" || "${identifier}" =~ ^[1-9][0-9]*$ ]]
}

has_valid_semver_prerelease_identifiers() {
    local prerelease="$1"
    local identifier
    local -a identifiers

    if [[ -z "${prerelease}" ]]; then
        return 0
    fi

    IFS='.' read -r -a identifiers <<< "${prerelease}"
    for identifier in "${identifiers[@]}"; do
        if [[ "${identifier}" =~ ^[0-9]+$ ]] \
            && ! is_valid_semver_numeric_identifier "${identifier}"; then
            return 1
        fi
    done
}

decimal_is_less_than() {
    local left="$1"
    local right="$2"
    local LC_ALL=C

    if (( ${#left} != ${#right} )); then
        (( ${#left} < ${#right} ))
        return
    fi

    [[ "${left}" < "${right}" ]]
}

decimal_is_greater_than() {
    decimal_is_less_than "$2" "$1"
}

is_supported_compose_version() {
    local major="$1"
    local minor="$2"
    local patch="$3"
    local prerelease="$4"

    if decimal_is_less_than "${major}" "${MINIMUM_COMPOSE_VERSION_MAJOR}"; then
        return 1
    fi
    if decimal_is_greater_than "${major}" "${MINIMUM_COMPOSE_VERSION_MAJOR}"; then
        return 0
    fi

    if decimal_is_less_than "${minor}" "${MINIMUM_COMPOSE_VERSION_MINOR}"; then
        return 1
    fi
    if decimal_is_greater_than "${minor}" "${MINIMUM_COMPOSE_VERSION_MINOR}"; then
        return 0
    fi

    if decimal_is_less_than "${patch}" "${MINIMUM_COMPOSE_VERSION_PATCH}"; then
        return 1
    fi
    if decimal_is_greater_than "${patch}" "${MINIMUM_COMPOSE_VERSION_PATCH}"; then
        return 0
    fi

    # A prerelease has lower SemVer precedence than its corresponding release. At the threshold,
    # 2.17.0-rc.1 must not be treated as 2.17.0, while build metadata remains precedence-neutral.
    [[ -z "${prerelease}" ]]
}

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
if [[ ! "${compose_version}" =~ ${COMPOSE_VERSION_PATTERN} ]]; then
    echo "docker Compose must report a semantic version (for example 2.17.0) to use --wait-timeout" >&2
    exit 69
fi
compose_major="${BASH_REMATCH[1]}"
compose_minor="${BASH_REMATCH[2]}"
compose_patch="${BASH_REMATCH[3]}"
compose_suffix="${BASH_REMATCH[4]:-}"
compose_prerelease=""

# `+ds1-0ubuntu1~24.04.1` identifies a distro package and `-desktop.1` identifies a
# Docker Desktop build. Neither changes the upstream release's feature set. Other `-...`
# suffixes remain SemVer prereleases and retain their lower precedence at the minimum version.
if [[ -n "${compose_suffix}" ]]; then
    if [[ "${compose_suffix}" =~ ${COMPOSE_VENDOR_BUILD_SUFFIX_PATTERN} ]] \
        || [[ "${compose_suffix}" =~ ${COMPOSE_DESKTOP_SUFFIX_PATTERN} ]]; then
        :
    elif [[ "${compose_suffix}" =~ ${COMPOSE_PRERELEASE_SUFFIX_PATTERN} ]]; then
        compose_prerelease="${BASH_REMATCH[1]}"
    else
        echo "docker Compose must report a semantic version (for example 2.17.0) to use --wait-timeout" >&2
        exit 69
    fi
fi

if ! is_valid_semver_numeric_identifier "${compose_major}" \
    || ! is_valid_semver_numeric_identifier "${compose_minor}" \
    || ! is_valid_semver_numeric_identifier "${compose_patch}" \
    || ! has_valid_semver_prerelease_identifiers "${compose_prerelease}"; then
    echo "docker Compose must report a semantic version (for example 2.17.0) to use --wait-timeout" >&2
    exit 69
fi

if ! is_supported_compose_version \
    "${compose_major}" "${compose_minor}" "${compose_patch}" "${compose_prerelease}"; then
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
