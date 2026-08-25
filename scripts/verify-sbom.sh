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

jq --exit-status --slurp '
    def purl_qualifier_keys:
        ((split("?")[1] // "") | (split("#")[0] // ""))
        | if . == "" then [] else split("&") | map(split("=")[0]) end;

    def valid_percent_encoding:
        gsub("%[0-9A-Fa-f]{2}"; "") | contains("%") | not;

    def valid_purl_characters:
        test("^[A-Za-z0-9._~%:/@?=&#-]+$")
        and (contains("[") | not)
        and (contains("]") | not);

    # Only encoded separators and dot characters can change these structural checks.
    def decode_purl_path_safety_characters:
        gsub("%2[fF]"; "/")
        | gsub("%2[eE]"; ".");

    def purl_namespace_segments:
        split("?")[0]
        | split("#")[0]
        | sub("^pkg:[^/]+/"; "")
        | split("/")
        | .[0:-1];

    def purl_name_segment:
        split("?")[0]
        | split("#")[0]
        | sub("^pkg:[^/]+/"; "")
        | split("/")
        | last
        | split("@")[0];

    def purl_subpath_segments:
        split("#") as $parts
        | if ($parts | length) == 2 then $parts[1] | split("/") else [] end;

    def valid_namespace_segment:
        decode_purl_path_safety_characters
        | contains("/") | not;

    def valid_name_segment:
        decode_purl_path_safety_characters
        | contains("/") | not;

    def valid_subpath_segment:
        decode_purl_path_safety_characters as $segment
        | ($segment | contains("/") | not)
        and $segment != "."
        and $segment != "..";

    def valid_purl:
        if type != "string" then false
        elif (valid_purl_characters | not) then false
        # Raw @, =, and & are structural delimiters, not path or version data.
        # The qualifier expression below continues to allow = and & as separators.
        elif (test("^pkg:[a-z][a-z0-9.-]+/(?:[^/?#@=&[:space:]]+/)*[^/?#@=&[:space:]]+(?:@[^/?#@=&[:space:]]+)?(?:\\?[a-z][a-z0-9._-]*=[^?=&#[:space:]]+(?:&[a-z][a-z0-9._-]*=[^?=&#[:space:]]+)*)?(?:#(?:[^/?#[:space:]]+/)*[^/?#[:space:]]+)?$") | not) then false
        elif (valid_percent_encoding | not) then false
        else
            purl_qualifier_keys as $keys
            | ($keys | length) == ($keys | unique | length)
            and (purl_namespace_segments | all(.[]; valid_namespace_segment))
            and (purl_name_segment | valid_name_segment)
            and (purl_subpath_segments | all(.[]; valid_subpath_segment))
        end;

    def supports_cryptographic_assets($spec_version):
        if ($spec_version | type) != "string" then false
        elif ($spec_version | test("^[0-9]+\\.[0-9]+$") | not) then false
        else
            ($spec_version | split(".") | map(tonumber)) as $version
            | $version[0] > 1
            or ($version[0] == 1 and $version[1] >= 6)
        end;

    def valid_component_type($spec_version):
        . as $component_type
        | ($component_type | type) == "string"
        and (
            [
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
                "data"
            ]
            + (if supports_cryptographic_assets($spec_version) then ["cryptographic-asset"] else [] end)
            | index($component_type) != null
        );

    def valid_component($spec_version):
        if type != "object" then false
        elif (.name | type) != "string" then false
        elif (.type | valid_component_type($spec_version) | not) then false
        elif .type == "library" and ((.purl? | valid_purl) | not) then false
        elif (has("components") | not) then true
        elif (.components | type) != "array" then false
        else all(.components[]; valid_component($spec_version))
        end;

    def valid_metadata($spec_version):
        if (has("metadata") | not) then true
        elif (.metadata | type) != "object" then false
        elif (.metadata | has("component") | not) then true
        else (.metadata.component | valid_component($spec_version))
        end;

    def valid_components($spec_version):
        if (has("components") | not) then true
        elif (.components | type) != "array" then false
        else all(.components[]; valid_component($spec_version))
        end;

    def valid_sbom:
        if type != "object" then false
        else
            .specVersion as $spec_version
            | .bomFormat == "CycloneDX"
            and ($spec_version | type == "string")
            and valid_metadata($spec_version)
            and valid_components($spec_version)
        end;

    if length != 1 then
        error("CycloneDX SBOM must contain exactly one JSON document")
    else
        .[0] | valid_sbom
    end
' -- "${SBOM_PATH}" >/dev/null

printf '%s\n' "Validated CycloneDX SBOM: ${SBOM_PATH}"
