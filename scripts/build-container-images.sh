#!/usr/bin/env bash
set -euo pipefail

if ! command -v dirname >/dev/null 2>&1; then
    echo "dirname is required to resolve the repository root" >&2
    exit 69
fi

REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly REPOSITORY_ROOT
readonly IMAGE_PREFIX="${LIFEOS_IMAGE_PREFIX:-lifeos}"
readonly IMAGE_TAG="${LIFEOS_IMAGE_TAG:-local}"
readonly PUSH_IMAGES="${LIFEOS_PUSH_IMAGES:-false}"
readonly IMAGE_NAME_COMPONENT_PATTERN='[a-z0-9]+(([._]|__|-+)[a-z0-9]+)*'
readonly IMAGE_REGISTRY_HOST_COMPONENT_PATTERN='[a-z0-9]([a-z0-9-]*[a-z0-9])?'
# Bracketed IPv6 registry hosts need full IPv6 parsing to distinguish malformed values such as
# "[aaaa]". Until that parser is available, accept only DNS-style registry hosts rather than
# allowing an invalid generated reference to reach Docker.
readonly IMAGE_REGISTRY_HOST_PATTERN="${IMAGE_REGISTRY_HOST_COMPONENT_PATTERN}(\.${IMAGE_REGISTRY_HOST_COMPONENT_PATTERN})*"
readonly IMAGE_TAG_PATTERN='[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}'
readonly IMAGE_REFERENCE_PATTERN="^(((${IMAGE_REGISTRY_HOST_PATTERN})(:[0-9]+)?)/)?${IMAGE_NAME_COMPONENT_PATTERN}(/${IMAGE_NAME_COMPONENT_PATTERN})*:${IMAGE_TAG_PATTERN}$"
readonly IMAGE_REPOSITORY_PATH_MAX_LENGTH=255
SERVICES=()

for service_discovery_command in find basename sort; do
    if ! command -v "${service_discovery_command}" >/dev/null 2>&1; then
        echo "${service_discovery_command} is required to discover service Dockerfiles" >&2
        exit 69
    fi
done

if ! discovered_services="$(find "${REPOSITORY_ROOT}/infrastructure/docker" -maxdepth 1 -type f -name '*.Dockerfile' \
    -exec basename {} .Dockerfile \; | sort)"; then
    echo "Failed to discover service Dockerfiles" >&2
    exit 69
fi

# Command substitution removes trailing newlines. Do not feed an empty successful discovery into
# the loop because a here-string would otherwise create one empty service instead of preserving
# the existing no-Dockerfiles failure below.
if [[ -n "${discovered_services}" ]]; then
    while IFS= read -r service; do
        SERVICES+=("${service}")
    done <<< "${discovered_services}"
fi
readonly SERVICES

# Validate the fully assembled reference so malformed registry ports, repository segments, tags,
# and Dockerfile-derived service names fail before any image build or push is attempted.
validate_image_reference() {
    local image_reference="$1"
    local repository_name
    local registry_candidate
    local repository_path

    if [[ ! "${image_reference}" =~ ${IMAGE_REFERENCE_PATTERN} ]]; then
        printf 'Invalid container image reference %q generated from LIFEOS_IMAGE_PREFIX and LIFEOS_IMAGE_TAG\n' \
            "${image_reference}" >&2
        return 1
    fi

    # Tags always follow the final colon in a syntactically valid reference, so this preserves a
    # registry port. Docker's normalized-name convention treats the first component as a registry
    # only when it is localhost or contains a dot or colon; the 255-character limit applies to
    # the remaining repository path, not that registry component.
    repository_name="${image_reference%:*}"
    registry_candidate="${repository_name%%/*}"
    repository_path="${repository_name}"
    if [[ "${repository_name}" == */* ]] \
        && [[ "${registry_candidate}" == "localhost" \
            || "${registry_candidate}" == *.* \
            || "${registry_candidate}" == *:* ]]; then
        repository_path="${repository_name#*/}"
    fi

    if (( ${#repository_path} > IMAGE_REPOSITORY_PATH_MAX_LENGTH )); then
        printf 'Invalid container image reference %q generated from LIFEOS_IMAGE_PREFIX and LIFEOS_IMAGE_TAG\n' \
            "${image_reference}" >&2
        return 1
    fi
}

if ! command -v docker >/dev/null 2>&1; then
    echo "docker is required to build LifeOS container images" >&2
    exit 69
fi

case "${PUSH_IMAGES}" in
    true|false) ;;
    *)
        echo "LIFEOS_PUSH_IMAGES must be either true or false" >&2
        exit 64
        ;;
esac

if [[ "${#SERVICES[@]}" -eq 0 ]]; then
    echo "No service Dockerfiles found in infrastructure/docker" >&2
    exit 66
fi

for service in "${SERVICES[@]}"; do
    if ! validate_image_reference "${IMAGE_PREFIX}/${service}:${IMAGE_TAG}"; then
        exit 64
    fi
done

for service in "${SERVICES[@]}"; do
    jar_directory="${REPOSITORY_ROOT}/services/${service}/build/libs"
    if [[ ! -d "${jar_directory}" ]]; then
        echo "Expected exactly one non-plain executable jar for ${service}; add the matching service module and run ./gradlew packageServices first" >&2
        exit 66
    fi

    shopt -s nullglob
    candidate_jars=("${jar_directory}"/*.jar)
    shopt -u nullglob
    jars=()
    for candidate_jar in "${candidate_jars[@]-}"; do
        # `jar` can coexist with Spring Boot's executable `bootJar`; only the latter belongs in a
        # runtime image. This also keeps local builds reliable after a developer has run `build`.
        [[ -z "${candidate_jar}" ]] && continue
        if [[ "${candidate_jar}" != *-plain.jar ]]; then
            jars+=("${candidate_jar}")
        fi
    done
    if [[ "${#jars[@]}" -ne 1 ]]; then
        echo "Expected exactly one non-plain executable jar for ${service}; run ./gradlew packageServices first" >&2
        exit 66
    fi

    image="${IMAGE_PREFIX}/${service}:${IMAGE_TAG}"
    jar_file="${jars[0]#"${REPOSITORY_ROOT}"/}"
    docker build \
        --build-arg "JAR_FILE=${jar_file}" \
        --file "${REPOSITORY_ROOT}/infrastructure/docker/${service}.Dockerfile" \
        --tag "${image}" \
        "${REPOSITORY_ROOT}"
    printf '%s\n' "Built ${image}"

    if [[ "${PUSH_IMAGES}" == "true" ]]; then
        docker push "${image}"
        printf '%s\n' "Pushed ${image}"
    fi
done
