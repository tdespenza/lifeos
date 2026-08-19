#!/usr/bin/env bash
set -euo pipefail

readonly REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
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
    .bomFormat == "CycloneDX"
    and (.specVersion | type == "string")
    and (.metadata.component.name | type == "string")
    and (.components | type == "array")
    and ([.components[] | select(.type == "library" and (.purl | type == "string"))] | length > 0)
' "${SBOM_PATH}" >/dev/null

printf '%s\n' "Validated CycloneDX SBOM: ${SBOM_PATH}"
