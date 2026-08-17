# ADR-021: Use a least-privilege trust boundary for CodeRabbit review requests

## Status

Accepted

## Context

Every non-draft LifeOS pull request must receive one CodeRabbit full review for
each pull-request head commit, including pull requests opened from forks. A
fork's workflow cannot safely receive a write-capable token through the normal
`pull_request` event, while a `pull_request_target` workflow executes in the
context of the base repository and can write a pull-request conversation.

`pull_request_target` is therefore a security boundary: checking out or
executing pull-request code in that workflow could allow untrusted code to use
the repository token. The workflow must remain a small, auditable API-only
operation with explicit permissions and deterministic de-duplication.

## Decision

The repository uses
[`.github/workflows/coderabbit-review.yml`](../../.github/workflows/coderabbit-review.yml)
with the following invariants:

- The workflow uses `pull_request_target` only for `opened`, `reopened`,
  `synchronize`, and `ready_for_review` events, and skips draft pull requests.
- Workflow-level permissions are default-deny (`permissions: {}`). The job has
  only `issues: write` and `pull-requests: write`, which GitHub requires for the
  single issue-style conversation comment that requests CodeRabbit on a pull
  request. The explicit pull-request permission is retained because the
  repository's `pull_request_target` token returned HTTP 403 when the job was
  restricted to `issues: write` alone.
- The workflow never checks out, imports, or executes pull-request code. Its
  shell step contains only hardcoded GitHub API and marker logic.
- A concurrency group is keyed by both pull-request number and head SHA, and
  runs are not cancelled. This prevents same-head races while allowing a new
  head commit to receive its own request.
- The workflow writes a marker containing the exact head SHA and searches all
  paginated issue comments before posting. Only comments authored by
  `github-actions[bot]` count as trusted workflow markers.
- The source-contract test
  `scripts/test-coderabbit-review-workflow.sh` asserts the trigger, draft gate,
  concurrency, permissions, no-checkout boundary, pagination, exact marker,
  author filter, and review command. Changes to these invariants must update
  that test in the same change.

## Consequences

Fork pull requests can receive automated review requests without executing
untrusted code with a write-capable token. The workflow has no general-purpose
automation capability, so changes that need additional GitHub writes require a
new security review and an explicit ADR update. Static contract validation does
not replace GitHub integration testing, but it provides deterministic local and
CI protection against accidental permission, trigger, checkout, pagination,
or de-duplication regressions.
