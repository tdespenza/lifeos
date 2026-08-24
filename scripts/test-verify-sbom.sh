#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
VERIFY_SBOM="$SCRIPT_DIR/verify-sbom.sh"
FIXTURE_DIR="$SCRIPT_DIR/test-fixtures/sbom"

assert_fails() {
    local description="$1"
    shift
    if "$@"; then
        echo "FAIL: $description (command unexpectedly succeeded)" >&2
        exit 1
    fi
}

bash "$VERIFY_SBOM" "$FIXTURE_DIR/valid-library-purls.json"
assert_fails "a library component without a PURL is rejected" \
    bash "$VERIFY_SBOM" "$FIXTURE_DIR/missing-library-purl.json"
assert_fails "a library component with a malformed PURL is rejected" \
    bash "$VERIFY_SBOM" "$FIXTURE_DIR/malformed-library-purl.json"

echo "CycloneDX SBOM PURL validation tests passed"
