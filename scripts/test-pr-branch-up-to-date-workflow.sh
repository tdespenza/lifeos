#!/usr/bin/env bash

set -euo pipefail

workflow_file="${1:-.github/workflows/pr-branch-up-to-date.yml}"

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

assert_not_contains() {
    local unexpected="$1"
    if grep -Fq -- "${unexpected}" "${workflow_file}"; then
        echo "Workflow contract must not contain: ${unexpected}" >&2
        exit 1
    fi
}

# The workflow is intentionally API-only. These source-contract assertions protect the
# merge gate and its pull_request_target trust boundary without contacting GitHub.
assert_contains 'pull_request_target:'
assert_contains 'types: [opened, reopened, synchronize, ready_for_review]'
assert_contains 'permissions: {}'
assert_contains 'name: Branch is up to date'
assert_contains '      contents: read'
assert_contains 'github.event.pull_request.base.sha'
assert_contains 'github.event.pull_request.head.sha'
assert_contains 'gh api'
assert_contains '/compare/${BASE_SHA}...${HEAD_SHA}'
assert_contains '.behind_by'
assert_contains 'behind|diverged)'
assert_contains 'exit 1'
assert_not_contains 'uses: actions/checkout'

echo "PR branch freshness workflow contract passed: ${workflow_file}"
