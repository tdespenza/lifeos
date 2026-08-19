#!/usr/bin/env bash
set -euo pipefail

readonly REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly IMAGE_PREFIX="${LIFEOS_IMAGE_PREFIX:-lifeos}"
readonly IMAGE_TAG="${LIFEOS_IMAGE_TAG:-local}"
readonly PUSH_IMAGES="${LIFEOS_PUSH_IMAGES:-false}"
SERVICES=()

while IFS= read -r service; do
    SERVICES+=("${service}")
done < <(find "${REPOSITORY_ROOT}/infrastructure/docker" -maxdepth 1 -type f -name '*.Dockerfile' \
    -exec basename {} .Dockerfile \; | sort)
readonly SERVICES

if ! command -v docker >/dev/null 2>&1; then
    echo "docker is required to build LifeOS container images" >&2
    exit 69
fi

if [[ ! "${IMAGE_PREFIX}" =~ ^[a-z0-9][a-z0-9./:-]*$ ]]; then
    echo "LIFEOS_IMAGE_PREFIX must be a lower-case container repository prefix" >&2
    exit 64
fi

if [[ ! "${IMAGE_TAG}" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$ ]]; then
    echo "LIFEOS_IMAGE_TAG must be a valid container tag" >&2
    exit 64
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
