#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
VERSION_HELPER="$SCRIPT_DIR/release-version.sh"
PROJECT_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"

assert_equal() {
  local expected="$1"
  local actual="$2"
  local description="$3"
  if [[ "$expected" != "$actual" ]]; then
    echo "FAIL: $description (expected '$expected', got '$actual')" >&2
    exit 1
  fi
}

assert_fails() {
  local description="$1"
  shift
  if "$@"; then
    echo "FAIL: $description (command unexpectedly succeeded)" >&2
    exit 1
  fi
}

PROJECT_VERSION=$(bash "$VERSION_HELPER" read "$PROJECT_ROOT/build.gradle.kts")
[[ "$PROJECT_VERSION" == *-SNAPSHOT ]] || {
  echo "FAIL: dev version must retain the -SNAPSHOT suffix" >&2
  exit 1
}

assert_equal "0.1.0" "$(bash "$VERSION_HELPER" next 0.1.0-SNAPSHOT patch)" "initial release"
assert_equal "0.1.1" "$(bash "$VERSION_HELPER" next 0.1.0-SNAPSHOT patch 0.1.0)" "patch release"
assert_equal "0.2.0" "$(bash "$VERSION_HELPER" next 0.1.0-SNAPSHOT minor 0.1.0)" "minor release"
assert_equal "1.0.0" "$(bash "$VERSION_HELPER" next 0.1.0-SNAPSHOT major 0.1.0)" "major release"
assert_equal "0.3.0" "$(bash "$VERSION_HELPER" next 0.3.0-SNAPSHOT patch 0.2.0)" "preserve staged future release"
assert_equal "0.1.1-SNAPSHOT" "$(bash "$VERSION_HELPER" next-snapshot 0.1.0)" "next development version"
assert_equal "-1" "$(bash "$VERSION_HELPER" compare 0.1.0 0.1.1)" "version less-than"
assert_equal "0" "$(bash "$VERSION_HELPER" compare 1.0.0 1.0.0)" "version equality"
assert_equal "1" "$(bash "$VERSION_HELPER" compare 1.0.0 0.9.9)" "version greater-than"
assert_fails "leading-zero SemVer is rejected" bash "$VERSION_HELPER" compare 01.0.0 1.0.0

TEMP_VERSION_FILE=$(mktemp)
trap 'rm -f "$TEMP_VERSION_FILE"' EXIT
cp "$PROJECT_ROOT/build.gradle.kts" "$TEMP_VERSION_FILE"
bash "$VERSION_HELPER" set "$TEMP_VERSION_FILE" 1.2.3-SNAPSHOT
assert_equal "1.2.3-SNAPSHOT" "$(bash "$VERSION_HELPER" read "$TEMP_VERSION_FILE")" "version source update"

echo "release-version helper tests passed"
