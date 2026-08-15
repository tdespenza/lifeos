#!/usr/bin/env bash

set -euo pipefail

workflow_file="${1:-.github/workflows/coderabbit-review.yml}"

if [[ ! -f "${workflow_file}" ]]; then
    echo "Workflow file not found: ${workflow_file}" >&2
    exit 1
fi

assert_contains() {
    local expected="$1"
    if ! grep -Fq -- "${expected}" "${workflow_file}"; then
        echo "Workflow contract missing: ${expected}" >&2
        exit 1
    fi
}

# This workflow intentionally uses only GitHub API shell commands and does not check out or
# execute pull-request code. These source-contract assertions deterministically exercise the
# security-sensitive behavior that can be validated without contacting GitHub.
assert_contains 'if: ${{ github.event.pull_request.draft == false }}'
assert_contains 'marker="<!-- lifeos-coderabbit-review:${HEAD_SHA} -->"'
assert_contains 'grep -Fq "${marker}" "${comments_file}"'
assert_contains 'select(.user.login == "github-actions[bot]")'
assert_contains '@coderabbitai full review'

echo "CodeRabbit review workflow contract passed: ${workflow_file}"
