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

    def valid_metadata:
        if (has("metadata") | not) then true
        elif (.metadata | type) != "object" then false
        elif (.metadata | has("component") | not) then true
        elif (.metadata.component | type) != "object" then false
        elif (.metadata.component | has("name") | not) then true
        else (.metadata.component.name | type == "string")
        end;

    def valid_components:
        if (has("components") | not) then true
        elif (.components | type) != "array" then false
        else
            all(
                .components[];
                if type != "object" then false
                elif .type == "library" then (.purl? | valid_purl)
                else true end
            )
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
