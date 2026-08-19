#!/usr/bin/env bash
set -euo pipefail

readonly REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERVICES=()
CONTRACT_PACKAGE_ROOTS=()

while IFS= read -r service_directory; do
    SERVICES+=("${service_directory}")
done < <(find "${REPOSITORY_ROOT}/services" -mindepth 1 -maxdepth 1 -type d -name '*-service' | sort)
readonly SERVICES

# A contract module is the only allowed shared Java namespace. Discover its first package segment
# rather than hard-coding event names, while keeping direct service-to-service imports prohibited.
if [[ -d "${REPOSITORY_ROOT}/contracts" ]]; then
    while IFS= read -r package_root; do
        CONTRACT_PACKAGE_ROOTS+=("${package_root}")
    done < <(find "${REPOSITORY_ROOT}/contracts" -type d -path '*/src/main/java/com/lifeos/*' \
        -prune -exec basename {} \; | sort -u)
fi
readonly CONTRACT_PACKAGE_ROOTS

is_contract_package() {
    local imported_package="$1"
    local contract_package_root

    for contract_package_root in "${CONTRACT_PACKAGE_ROOTS[@]-}"; do
        if [[ "${imported_package}" == "com.lifeos.${contract_package_root}."* ]]; then
            return 0
        fi
    done
    return 1
}

if [[ "${#SERVICES[@]}" -eq 0 ]]; then
    echo "No independently deployable service directories were found" >&2
    exit 66
fi

for service_directory in "${SERVICES[@]}"; do
    service="$(basename "${service_directory}")"
    main_source_directory="${service_directory}/src/main/java"
    resources_directory="${service_directory}/src/main/resources"
    dockerfile="${REPOSITORY_ROOT}/infrastructure/docker/${service}.Dockerfile"

    for required in "${service_directory}/build.gradle.kts" "${main_source_directory}" \
        "${resources_directory}/application.yml" "${dockerfile}"; do
        if [[ ! -e "${required}" ]]; then
            echo "${service} violates the service-boundary contract: missing ${required#"${REPOSITORY_ROOT}"/}" >&2
            exit 65
        fi
    done

    if ! grep -Eq 'id\("org\.springframework\.boot"\)' "${service_directory}/build.gradle.kts"; then
        echo "${service} must explicitly apply the Spring Boot plugin" >&2
        exit 65
    fi

    if ! grep -REq '@SpringBootApplication' "${main_source_directory}"; then
        echo "${service} has no Spring Boot application entry point" >&2
        exit 65
    fi

    if ! grep -Eq '^USER [^[:space:]]+' "${dockerfile}" \
        || grep -Eq '^USER (root|0)(:|$)' "${dockerfile}"; then
        echo "${service} Dockerfile must run as a non-root user" >&2
        exit 65
    fi

    if ! grep -Eq '^ARG JAR_FILE$' "${dockerfile}" \
        || ! grep -Eq '^COPY .*\$\{JAR_FILE\} /app/app\.jar$' "${dockerfile}"; then
        echo "${service} Dockerfile must copy the build-selected executable Spring Boot jar" >&2
        exit 65
    fi

    # Container scans report image-base vulnerabilities, but a mutable base tag can change between
    # otherwise identical builds. Require every stage to name an immutable digest as well.
    if grep -E '^FROM ' "${dockerfile}" | grep -Eqv '@sha256:[0-9a-f]{64}([[:space:]]|$)'; then
        echo "${service} Dockerfile must pin every base image with a sha256 digest" >&2
        exit 65
    fi

    package_roots=()
    while IFS= read -r package_root; do
        package_roots+=("${package_root}")
    done < <(find "${main_source_directory}/com/lifeos" -mindepth 1 -maxdepth 1 -type d -exec basename {} \; | sort)

    if [[ "${#package_roots[@]}" -ne 1 ]]; then
        echo "${service} must own exactly one top-level com.lifeos package" >&2
        exit 65
    fi

    package_root="${package_roots[0]}"
    while IFS= read -r imported_package; do
        imported_package="${imported_package#import }"
        if [[ "${imported_package}" != "com.lifeos.${package_root}."* ]] \
            && ! is_contract_package "${imported_package}"; then
            echo "${service} directly imports another service package: ${imported_package}" >&2
            exit 65
        fi
    done < <(grep -REho '^import com\.lifeos\.[A-Za-z0-9_.]+' "${main_source_directory}" || true)

    printf '%s\n' "Architecture boundary verified for ${service}"
done
