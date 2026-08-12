#!/usr/bin/env bash

# Shared, dependency-free SemVer helpers for the release workflows.
# Stable releases use MAJOR.MINOR.PATCH, with -SNAPSHOT reserved for dev.

set -euo pipefail

readonly STABLE_SEMVER='^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'
readonly DEV_SEMVER='^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-SNAPSHOT)?$'

usage() {
  cat >&2 <<'EOF'
Usage:
  release-version.sh read <build.gradle.kts>
  release-version.sh set <build.gradle.kts> <version>
  release-version.sh next <current-version> <patch|minor|major> [latest-release]
  release-version.sh next-snapshot <release-version>
  release-version.sh compare <left-version> <right-version>
EOF
  exit 2
}

require_dev_version() {
  local version="$1"
  [[ "$version" =~ $DEV_SEMVER ]] || {
    echo "invalid project version '$version' (expected MAJOR.MINOR.PATCH[-SNAPSHOT])" >&2
    exit 1
  }
}

require_stable_version() {
  local version="$1"
  [[ "$version" =~ $STABLE_SEMVER ]] || {
    echo "invalid stable version '$version' (expected MAJOR.MINOR.PATCH)" >&2
    exit 1
  }
}

compare_stable_versions() {
  local left="$1"
  local right="$2"
  require_stable_version "$left"
  require_stable_version "$right"

  local left_major left_minor left_patch right_major right_minor right_patch
  IFS=. read -r left_major left_minor left_patch <<< "$left"
  IFS=. read -r right_major right_minor right_patch <<< "$right"

  if ((left_major < right_major ||
       (left_major == right_major && left_minor < right_minor) ||
       (left_major == right_major && left_minor == right_minor && left_patch < right_patch))); then
    echo -1
  elif ((left_major == right_major && left_minor == right_minor && left_patch == right_patch)); then
    echo 0
  else
    echo 1
  fi
}

bump_version() {
  local version="$1"
  local bump="$2"
  require_stable_version "$version"

  local major minor patch
  IFS=. read -r major minor patch <<< "$version"
  case "$bump" in
    major)
      printf '%s\n' "$((major + 1)).0.0"
      ;;
    minor)
      printf '%s\n' "$major.$((minor + 1)).0"
      ;;
    patch)
      printf '%s\n' "$major.$minor.$((patch + 1))"
      ;;
    *)
      echo "invalid release bump '$bump' (expected patch, minor, or major)" >&2
      exit 1
      ;;
  esac
}

read_project_version() {
  local file="$1"
  [[ -f "$file" ]] || {
    echo "version source '$file' does not exist" >&2
    exit 1
  }

  local version
  version=$(awk -F'"' '/^[[:space:]]*version[[:space:]]*=[[:space:]]*"/ { print $2; exit }' "$file")
  [[ -n "$version" ]] || {
    echo "could not find a version assignment in '$file'" >&2
    exit 1
  }
  require_dev_version "$version"
  printf '%s\n' "$version"
}

set_project_version() {
  local file="$1"
  local version="$2"
  require_dev_version "$version"
  [[ -f "$file" ]] || {
    echo "version source '$file' does not exist" >&2
    exit 1
  }

  local temporary_file
  temporary_file=$(mktemp "${file}.tmp.XXXXXX")
  trap 'rm -f "$temporary_file"' EXIT
  awk -v replacement="$version" '
    BEGIN { found = 0 }
    !found && $0 ~ /^[[:space:]]*version[[:space:]]*=[[:space:]]*"/ {
      sub(/"[^"]*"/, "\"" replacement "\"")
      found = 1
    }
    { print }
    END { if (!found) exit 2 }
  ' "$file" > "$temporary_file" || {
    echo "could not update the version assignment in '$file'" >&2
    exit 1
  }
  mv "$temporary_file" "$file"
  trap - EXIT
}

next_release_version() {
  local current_version="$1"
  local bump="$2"
  local latest_release="${3:-}"
  require_dev_version "$current_version"

  local current_base="${current_version%-SNAPSHOT}"
  require_stable_version "$current_base"

  local candidate
  if [[ -n "$latest_release" ]]; then
    require_stable_version "$latest_release"
    candidate=$(bump_version "$latest_release" "$bump")
  else
    # The first release adopts the version already declared on dev. After the
    # first release, the latest tag is the source of truth for the bump.
    candidate="$current_base"
  fi

  if [[ -n "$latest_release" ]] && [[ "$(compare_stable_versions "$current_base" "$candidate")" == "1" ]]; then
    # A maintainer may intentionally stage a future version on dev. Preserve
    # that explicit choice as long as it is newer than the latest release.
    candidate="$current_base"
  fi
  printf '%s\n' "$candidate"
}

case "${1:-}" in
  read)
    [[ $# -eq 2 ]] || usage
    read_project_version "$2"
    ;;
  set)
    [[ $# -eq 3 ]] || usage
    set_project_version "$2" "$3"
    ;;
  next)
    [[ $# -ge 3 && $# -le 4 ]] || usage
    next_release_version "$2" "$3" "${4:-}"
    ;;
  next-snapshot)
    [[ $# -eq 2 ]] || usage
    printf '%s-SNAPSHOT\n' "$(bump_version "$2" patch)"
    ;;
  compare)
    [[ $# -eq 3 ]] || usage
    compare_stable_versions "$2" "$3"
    ;;
  *)
    usage
    ;;
esac
