#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
VERIFY_SBOM="$SCRIPT_DIR/verify-sbom.sh"
FIXTURE_DIR="$SCRIPT_DIR/test-fixtures/sbom"

assert_json_object_fixture() {
    local fixture="$1"

    if [[ ! -s "$fixture" ]]; then
        echo "FAIL: fixture is missing or empty: $fixture" >&2
        exit 1
    fi

    if ! jq --exit-status --slurp '
        length == 1 and (.[0] | type == "object")
    ' "$fixture" >/dev/null; then
        echo "FAIL: fixture is not a single valid JSON object: $fixture" >&2
        exit 1
    fi
}

assert_succeeds() {
    local description="$1"
    local fixture="$2"

    assert_json_object_fixture "$fixture"
    if ! bash "$VERIFY_SBOM" "$fixture"; then
        echo "FAIL: $description" >&2
        exit 1
    fi
}

assert_fails() {
    local description="$1"
    local fixture="$2"

    assert_json_object_fixture "$fixture"
    if bash "$VERIFY_SBOM" "$fixture"; then
        echo "FAIL: $description (command unexpectedly succeeded)" >&2
        exit 1
    fi
}

assert_multiple_documents_are_rejected() {
    local fixture="$1"
    local output

    if [[ ! -s "$fixture" ]]; then
        echo "FAIL: fixture is missing or empty: $fixture" >&2
        exit 1
    fi

    if ! jq --exit-status --slurp '
        length == 2 and all(.[]; type == "object")
    ' "$fixture" >/dev/null; then
        echo "FAIL: fixture must contain two valid JSON objects: $fixture" >&2
        exit 1
    fi

    if output="$(bash "$VERIFY_SBOM" "$fixture" 2>&1)"; then
        echo "FAIL: multiple top-level JSON documents are accepted" >&2
        exit 1
    fi

    if [[ "$output" != *"CycloneDX SBOM must contain exactly one JSON document"* ]]; then
        echo "FAIL: multiple top-level JSON documents produced an unexpected diagnostic" >&2
        exit 1
    fi
}

assert_option_like_filename_is_rejected() {
    local temporary_directory

    temporary_directory="$(mktemp -d)"
    assert_json_object_fixture "$FIXTURE_DIR/invalid-component-type.json"
    cp "$FIXTURE_DIR/invalid-component-type.json" "$temporary_directory/--version"

    if (cd "$temporary_directory" && bash "$VERIFY_SBOM" --version); then
        rm -rf -- "$temporary_directory"
        echo "FAIL: an option-like SBOM filename bypassed validation" >&2
        exit 1
    fi

    rm -rf -- "$temporary_directory"
}

assert_succeeds "valid library PURLs are accepted" "$FIXTURE_DIR/valid-library-purls.json"
assert_succeeds "percent-encoded PURL special characters are accepted" \
    "$FIXTURE_DIR/valid-percent-encoded-purl-characters.json"
assert_succeeds "valid percent-encoded UTF-8 PURL characters are accepted" \
    "$FIXTURE_DIR/valid-percent-encoded-utf8-purl.json"
assert_succeeds "a minimal CycloneDX 1.5 SBOM is accepted" "$FIXTURE_DIR/minimal-cyclonedx-1.5.json"
assert_succeeds "a CycloneDX 1.6 cryptographic asset is accepted" "$FIXTURE_DIR/valid-cryptographic-asset-1.6.json"
assert_succeeds "a later CycloneDX cryptographic asset is accepted" "$FIXTURE_DIR/valid-cryptographic-asset-1.7.json"
assert_succeeds "an SBOM without metadata is accepted" "$FIXTURE_DIR/without-metadata.json"
assert_succeeds "an SBOM without a metadata component is accepted" "$FIXTURE_DIR/without-metadata-component.json"
assert_succeeds "an SBOM without components is accepted" "$FIXTURE_DIR/without-components.json"
assert_succeeds "an SBOM without library components is accepted" "$FIXTURE_DIR/without-library-components.json"
assert_succeeds "valid metadata tool components are accepted" \
    "$FIXTURE_DIR/valid-metadata-tool-components.json"
assert_succeeds "legacy metadata tool arrays are accepted" \
    "$FIXTURE_DIR/valid-legacy-metadata-tools.json"
assert_succeeds "unique bom-refs across all component locations are accepted" \
    "$FIXTURE_DIR/valid-global-bom-refs.json"
assert_fails "a library component without a PURL is rejected" \
    "$FIXTURE_DIR/missing-library-purl.json"
assert_fails "a library component with a malformed PURL is rejected" \
    "$FIXTURE_DIR/malformed-library-purl.json"
assert_fails "a non-library component with a malformed PURL is rejected" \
    "$FIXTURE_DIR/malformed-non-library-purl.json"
assert_fails "a nested library component without a PURL is rejected" \
    "$FIXTURE_DIR/nested-missing-library-purl.json"
assert_fails "a nested library component with a malformed PURL is rejected" \
    "$FIXTURE_DIR/nested-malformed-library-purl.json"
assert_fails "duplicate root component objects are rejected" \
    "$FIXTURE_DIR/duplicate-root-component-objects.json"
assert_fails "duplicate nested component objects are rejected" \
    "$FIXTURE_DIR/duplicate-nested-component-objects.json"
assert_fails "a metadata tool library component without a PURL is rejected" \
    "$FIXTURE_DIR/missing-purl-metadata-tool-component.json"
assert_fails "duplicate metadata tool component objects are rejected" \
    "$FIXTURE_DIR/duplicate-metadata-tool-component-objects.json"
assert_fails "a scalar metadata.tools value is rejected" \
    "$FIXTURE_DIR/invalid-scalar-metadata-tools.json"
assert_fails "a null metadata.tools value is rejected" \
    "$FIXTURE_DIR/invalid-null-metadata-tools.json"
assert_fails "a legacy metadata tool array with a scalar entry is rejected" \
    "$FIXTURE_DIR/invalid-legacy-metadata-tool.json"
assert_fails "duplicate root component bom-refs are rejected" \
    "$FIXTURE_DIR/duplicate-root-component-bom-refs.json"
assert_fails "duplicate nested component bom-refs are rejected" \
    "$FIXTURE_DIR/duplicate-nested-component-bom-refs.json"
assert_fails "duplicate metadata component bom-refs are rejected" \
    "$FIXTURE_DIR/duplicate-metadata-component-bom-refs.json"
assert_fails "duplicate metadata tool component bom-refs are rejected" \
    "$FIXTURE_DIR/duplicate-metadata-tool-component-bom-refs.json"
assert_fails "a non-string bom-ref is rejected" \
    "$FIXTURE_DIR/non-string-bom-ref.json"
assert_fails "a component without a name is rejected" \
    "$FIXTURE_DIR/missing-component-name.json"
assert_fails "a component with a non-string name is rejected" \
    "$FIXTURE_DIR/invalid-component-name.json"
assert_fails "a component without a type is rejected" \
    "$FIXTURE_DIR/missing-component-type.json"
assert_fails "a component with an unsupported type is rejected" \
    "$FIXTURE_DIR/invalid-component-type.json"
assert_fails "an empty metadata component is rejected" \
    "$FIXTURE_DIR/empty-metadata-component.json"
assert_fails "an invalid metadata component is rejected" \
    "$FIXTURE_DIR/invalid-metadata-component.json"
assert_fails "a library PURL with an invalid qualifier key is rejected" \
    "$FIXTURE_DIR/invalid-qualifier-key-purl.json"
assert_fails "a library PURL with duplicate qualifier keys is rejected" \
    "$FIXTURE_DIR/duplicate-qualifier-purl.json"
assert_fails "a library PURL with malformed percent encoding is rejected" \
    "$FIXTURE_DIR/malformed-percent-encoding-purl.json"
assert_fails "a library PURL with percent escapes that decode to invalid UTF-8 is rejected" \
    "$FIXTURE_DIR/invalid-percent-encoded-utf8-purl.json"
assert_fails "a library PURL with raw square brackets is rejected" \
    "$FIXTURE_DIR/raw-square-brackets-purl.json"
assert_fails "a library PURL with a raw backslash is rejected" \
    "$FIXTURE_DIR/raw-backslash-purl.json"
assert_fails "a library PURL with raw non-ASCII characters is rejected" \
    "$FIXTURE_DIR/raw-non-ascii-purl.json"
assert_fails "a library PURL with a raw ampersand in its namespace is rejected" \
    "$FIXTURE_DIR/raw-ampersand-namespace-purl.json"
assert_fails "a library PURL with a raw equals sign in its namespace is rejected" \
    "$FIXTURE_DIR/raw-equals-namespace-purl.json"
assert_fails "a library PURL with a raw at sign in its namespace is rejected" \
    "$FIXTURE_DIR/raw-at-sign-namespace-purl.json"
assert_fails "a library PURL with a raw ampersand in its name is rejected" \
    "$FIXTURE_DIR/raw-ampersand-name-purl.json"
assert_fails "a library PURL with a raw equals sign in its name is rejected" \
    "$FIXTURE_DIR/raw-equals-name-purl.json"
assert_fails "a library PURL with a raw ampersand in its version is rejected" \
    "$FIXTURE_DIR/raw-ampersand-version-purl.json"
assert_fails "a library PURL with a raw equals sign in its version is rejected" \
    "$FIXTURE_DIR/raw-equals-version-purl.json"
assert_fails "a library PURL with a raw at sign in its version is rejected" \
    "$FIXTURE_DIR/raw-at-sign-version-purl.json"
assert_fails "a library PURL with unencoded qualifier-value separators is rejected" \
    "$FIXTURE_DIR/unencoded-qualifier-value-separators-purl.json"
assert_fails "a library PURL with an unencoded equals sign in a qualifier value is rejected" \
    "$FIXTURE_DIR/unencoded-equals-qualifier-value-purl.json"
assert_fails "a library PURL with an encoded namespace separator is rejected" \
    "$FIXTURE_DIR/encoded-namespace-separator-purl.json"
assert_fails "a library PURL with an encoded name separator is rejected" \
    "$FIXTURE_DIR/encoded-name-separator-purl.json"
assert_fails "a library PURL with a dot-dot subpath is rejected" \
    "$FIXTURE_DIR/dot-dot-subpath-purl.json"
assert_fails "a library PURL with an encoded subpath separator is rejected" \
    "$FIXTURE_DIR/encoded-subpath-separator-purl.json"
assert_fails "a CycloneDX 1.5 cryptographic asset is rejected" \
    "$FIXTURE_DIR/invalid-cryptographic-asset-1.5.json"
assert_multiple_documents_are_rejected "$FIXTURE_DIR/multiple-top-level-documents.ndjson"
assert_option_like_filename_is_rejected

echo "CycloneDX SBOM validation tests passed"
