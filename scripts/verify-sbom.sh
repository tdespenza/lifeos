#!/usr/bin/env bash
set -euo pipefail

REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly REPOSITORY_ROOT
readonly SBOM_PATH="${1:-${REPOSITORY_ROOT}/build/reports/cyclonedx/bom.json}"
readonly SCHEMA_VALIDATOR="${REPOSITORY_ROOT}/scripts/validate-cyclonedx-schema.js"

if ! command -v jq >/dev/null 2>&1; then
    echo "jq is required to validate the CycloneDX SBOM" >&2
    exit 69
fi

if ! command -v node >/dev/null 2>&1; then
    echo "node is required to validate the CycloneDX SBOM schema" >&2
    exit 69
fi

if [[ ! -s "${SBOM_PATH}" ]]; then
    echo "CycloneDX SBOM is missing or empty: ${SBOM_PATH}" >&2
    exit 66
fi

jq --exit-status --slurp '
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

    # The official CycloneDX 1.5, 1.6, and 1.7 JSON schemas each set
    # component.additionalProperties to false. Keep the field contract
    # version-specific so a BOM cannot hide arbitrary data in a component.
    # Newer versions use the newest known contract until their schema is
    # explicitly modelled here; unknown fields still fail closed.
    def component_properties_1_5:
        [
            "author", "bom-ref", "components", "copyright", "cpe", "data",
            "description", "evidence", "externalReferences", "group", "hashes",
            "licenses", "mime-type", "modelCard", "modified", "name", "pedigree",
            "properties", "publisher", "purl", "releaseNotes", "scope", "signature",
            "supplier", "swid", "type", "version"
        ];

    def component_properties_1_6:
        component_properties_1_5
        + ["authors", "cryptoProperties", "manufacturer", "omniborId", "swhid", "tags"];

    def component_properties_1_7:
        component_properties_1_6 + ["isExternal", "patentAssertions", "versionRange"];

    def allowed_component_properties($spec_version):
        if $spec_version == "1.5" then component_properties_1_5
        elif $spec_version == "1.6" then component_properties_1_6
        else component_properties_1_7
        end;

    def valid_component_properties($spec_version):
        . as $component
        | allowed_component_properties($spec_version) as $allowed
        | (($component | keys) - $allowed | length) == 0;

    # Components are JSON objects, so exact duplicate objects are redundant and
    # make the SBOM ambiguous for downstream inventory consumers.
    def unique_component_objects:
        type == "array" and (length == (unique | length));

    # CycloneDX 1.7 models concrete component versions and accepted version
    # range as mutually exclusive. A range describes an externally provided
    # runtime component, so it is only meaningful when isExternal is true.
    # Keep this constraint scoped to 1.7: earlier schemas reject these fields
    # through their property contracts, and later schemas may evolve them.
    def valid_component_version_fields($spec_version):
        if $spec_version != "1.7" then true
        elif has("version") and has("versionRange") then false
        elif has("versionRange") and (.isExternal? != true) then false
        else true
        end;

    def valid_component($spec_version):
        if type != "object" then false
        elif (valid_component_properties($spec_version) | not) then false
        elif (valid_component_version_fields($spec_version) | not) then false
        elif (.name | type) != "string" then false
        elif (.type | valid_component_type($spec_version) | not) then false
        # PURLs are parsed by the direct packageurl-js dependency in the Node
        # validator so package URL rules are not reimplemented in jq. That
        # step also requires PURLs for library components.
        elif (has("components") | not) then true
        elif (.components | type) != "array" then false
        elif (.components | unique_component_objects | not) then false
        else all(.components[]; valid_component($spec_version))
        end;

    def valid_metadata_component($spec_version):
        . as $component
        | ($component | valid_component($spec_version))
        and (
            if $spec_version == "1.7" then
                ($component.isExternal? != true)
            else true
            end
        );

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
                ($metadata | if has("component") then .component | valid_metadata_component($spec_version) else true end)
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
        | all($refs[]; type == "string" and length > 0)
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

node "${SCHEMA_VALIDATOR}" "${SBOM_PATH}"

printf '%s\n' "Validated CycloneDX SBOM: ${SBOM_PATH}"
