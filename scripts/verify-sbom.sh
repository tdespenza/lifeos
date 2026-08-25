#!/usr/bin/env bash
set -euo pipefail

REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly REPOSITORY_ROOT
readonly SBOM_PATH="${1:-${REPOSITORY_ROOT}/build/reports/cyclonedx/bom.json}"

if ! command -v jq >/dev/null 2>&1; then
    echo "jq is required to validate the CycloneDX SBOM" >&2
    exit 69
fi

if [[ ! -s "${SBOM_PATH}" ]]; then
    echo "CycloneDX SBOM is missing or empty: ${SBOM_PATH}" >&2
    exit 66
fi

jq --exit-status '
    def purl_qualifier_keys:
        ((split("?")[1] // "") | (split("#")[0] // ""))
        | if . == "" then [] else split("&") | map(split("=")[0]) end;

    def valid_percent_encoding:
        gsub("%[0-9A-Fa-f]{2}"; "") | contains("%") | not;

    def valid_purl:
        if type != "string" then false
        elif (test("^pkg:[a-z][a-z0-9.-]+/(?:[^/?#[:space:]]+/)*[^/?#@[:space:]]+(?:@[^/?#[:space:]]+)?(?:\\?[a-z][a-z0-9._-]*=[^&#[:space:]]+(?:&[a-z][a-z0-9._-]*=[^&#[:space:]]+)*)?(?:#(?:[^/?#[:space:]]+/)*[^/?#[:space:]]+)?$") | not) then false
        elif (valid_percent_encoding | not) then false
        else
            purl_qualifier_keys as $keys
            | ($keys | length) == ($keys | unique | length)
        end;

    def valid_component_type:
        . as $component_type
        | ($component_type | type) == "string"
        and ([
            "application",
            "framework",
            "library",
            "container",
            "platform",
            "operating-system",
            "device",
            "device-driver",
            "firmware",
            "file",
            "machine-learning-model",
            "data",
            "cryptographic-asset"
        ] | index($component_type) != null);

    def valid_component:
        if type != "object" then false
        elif (.name | type) != "string" then false
        elif (.type | valid_component_type | not) then false
        elif .type == "library" and ((.purl? | valid_purl) | not) then false
        elif (has("components") | not) then true
        elif (.components | type) != "array" then false
        else all(.components[]; valid_component)
        end;

    def valid_metadata:
        if (has("metadata") | not) then true
        elif (.metadata | type) != "object" then false
        elif (.metadata | has("component") | not) then true
        else (.metadata.component | valid_component)
        end;

    def valid_components:
        if (has("components") | not) then true
        elif (.components | type) != "array" then false
        else all(.components[]; valid_component)
        end;

    if type != "object" then false
    else
        .bomFormat == "CycloneDX"
        and (.specVersion | type == "string")
        and valid_metadata
        and valid_components
    end
' "${SBOM_PATH}" >/dev/null

printf '%s\n' "Validated CycloneDX SBOM: ${SBOM_PATH}"
