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

    def hex_digit_value:
        . as $digit
        | "0123456789abcdef"
        | index($digit | ascii_downcase);

    def percent_encoded_byte:
        .[1:3] as $hex
        | (($hex[0:1] | hex_digit_value) * 16) + ($hex[1:2] | hex_digit_value);

    def decoded_purl_bytes:
        [scan("%[0-9A-Fa-f]{2}|[^%]")
         | if startswith("%") then percent_encoded_byte else explode[] end];

    # Package URLs use percent-encoded UTF-8.  Validate the decoded byte stream
    # without relying on jq replacement-character handling for invalid bytes.
    def valid_utf8_bytes:
        reduce .[] as $byte (
            {valid: true, remaining: 0, minimum: 128, maximum: 191};
            if (.valid | not) then .
            elif .remaining > 0 then
                if $byte >= .minimum and $byte <= .maximum then
                    .remaining -= 1 | .minimum = 128 | .maximum = 191
                else .valid = false end
            elif $byte <= 127 then .
            elif $byte >= 194 and $byte <= 223 then .remaining = 1
            elif $byte == 224 then .remaining = 2 | .minimum = 160
            elif ($byte >= 225 and $byte <= 236) or ($byte >= 238 and $byte <= 239) then .remaining = 2
            elif $byte == 237 then .remaining = 2 | .maximum = 159
            elif $byte == 240 then .remaining = 3 | .minimum = 144
            elif $byte >= 241 and $byte <= 243 then .remaining = 3
            elif $byte == 244 then .remaining = 3 | .maximum = 143
            else .valid = false end
        )
        | .valid and .remaining == 0;

    def valid_percent_decoded_utf8:
        decoded_purl_bytes | valid_utf8_bytes;

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
        elif (valid_percent_decoded_utf8 | not) then false
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

    # Components are JSON objects, so exact duplicate objects are redundant and
    # make the SBOM ambiguous for downstream inventory consumers.
    def unique_component_objects:
        type == "array" and (length == (unique | length));

    def valid_component($spec_version):
        if type != "object" then false
        elif (.name | type) != "string" then false
        elif (.type | valid_component_type($spec_version) | not) then false
        # A PURL is optional for non-library component types, but whenever a
        # component declares one it must be structurally valid. Libraries
        # remain required to declare a valid PURL for dependency traceability.
        elif .type == "library" and ((.purl? | valid_purl) | not) then false
        elif has("purl") and ((.purl | valid_purl) | not) then false
        elif (has("components") | not) then true
        elif (.components | type) != "array" then false
        elif (.components | unique_component_objects | not) then false
        else all(.components[]; valid_component($spec_version))
        end;

    def valid_metadata_tools($spec_version):
        # CycloneDX 1.5 permits either the modern object declaration or the
        # deprecated legacy array of tool objects. Keep validating modern tool
        # components with the same component rules used elsewhere in the BOM.
        if has("tools") | not then true
        else
            .tools as $tools
            | if ($tools | type) == "object" then
                if ($tools | has("components") | not) then true
                elif ($tools.components | type) != "array" then false
                elif ($tools.components | unique_component_objects | not) then false
                else all($tools.components[]; valid_component($spec_version))
                end
            elif ($tools | type) == "array" then
                all($tools[]; type == "object")
            else false
            end
        end;

    def valid_metadata($spec_version):
        if (has("metadata") | not) then true
        elif (.metadata | type) != "object" then false
        else
            .metadata as $metadata
            | (
                ($metadata | if has("component") then .component | valid_component($spec_version) else true end)
                and ($metadata | valid_metadata_tools($spec_version))
              )
        end;

    def valid_components($spec_version):
        if (has("components") | not) then true
        elif (.components | type) != "array" then false
        elif (.components | unique_component_objects | not) then false
        else all(.components[]; valid_component($spec_version))
        end;

    # A bom-ref is a global identifier within one BOM. Recursing through the
    # document covers root and nested components as well as metadata and tool
    # components, without maintaining a separate traversal for each location.
    def valid_bom_refs:
        [.. | objects | select(has("bom-ref")) | .["bom-ref"]] as $refs
        | all($refs[]; type == "string")
        and (($refs | length) == ($refs | unique | length));

    def valid_sbom:
        if type != "object" then false
        else
            .specVersion as $spec_version
            | .bomFormat == "CycloneDX"
            and ($spec_version | type == "string")
            and valid_bom_refs
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
