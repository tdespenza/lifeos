#!/usr/bin/env bash

# This file is sourced by image-building, image-scanning, and staging-deployment entrypoints.
# Keep repeated sourcing idempotent so shared readonly declarations are initialized only once in
# a caller's shell, matching scripts/https-authority-validation.sh.
if [[ "$(declare -p _LIFEOS_IMAGE_REFERENCE_VALIDATION_STATE 2>/dev/null)" == "declare -a "* ]]; then
    if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
        exit 0
    fi
    return 0
fi
_LIFEOS_IMAGE_REFERENCE_VALIDATION_STATE=()

readonly IMAGE_NAME_COMPONENT_PATTERN='[a-z0-9]+(([._]|__|-+)[a-z0-9]+)*'
readonly IMAGE_REGISTRY_HOST_COMPONENT_PATTERN='[a-z0-9]([a-z0-9-]*[a-z0-9])?'
# Bracketed IPv6 registry hosts need full IPv6 parsing to distinguish malformed values such as
# "[aaaa]". Until that parser is available, accept only DNS-style registry hosts rather than
# allowing an invalid generated reference to reach Docker or the deployment endpoint.
readonly IMAGE_REGISTRY_HOST_PATTERN="${IMAGE_REGISTRY_HOST_COMPONENT_PATTERN}(\.${IMAGE_REGISTRY_HOST_COMPONENT_PATTERN})*"
readonly IMAGE_TAG_PATTERN='[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}'
readonly IMAGE_REFERENCE_PATTERN="^(((${IMAGE_REGISTRY_HOST_PATTERN})(:[0-9]+)?)/)?${IMAGE_NAME_COMPONENT_PATTERN}(/${IMAGE_NAME_COMPONENT_PATTERN})*:${IMAGE_TAG_PATTERN}$"
# The Distribution reference parser limits the complete repository name (including an optional
# registry and port, but excluding the tag) to 255 characters.
readonly IMAGE_REPOSITORY_NAME_MAX_LENGTH=255

validate_image_reference() {
    local image_reference="$1"
    local repository_name

    if [[ ! "${image_reference}" =~ ${IMAGE_REFERENCE_PATTERN} ]]; then
        printf 'Invalid container image reference %q generated from LIFEOS_IMAGE_PREFIX and LIFEOS_IMAGE_TAG\n' \
            "${image_reference}" >&2
        return 1
    fi

    # Tags always follow the final colon in a syntactically valid reference, so this preserves a
    # registry port. Docker's reference parser limits this entire name, including any registry,
    # rather than only its slash-separated path.
    repository_name="${image_reference%:*}"

    if (( ${#repository_name} > IMAGE_REPOSITORY_NAME_MAX_LENGTH )); then
        printf 'Invalid container image reference %q generated from LIFEOS_IMAGE_PREFIX and LIFEOS_IMAGE_TAG\n' \
            "${image_reference}" >&2
        return 1
    fi
}
