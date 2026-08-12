# ADR-019: Automate Git Flow releases with versioned GitHub Actions

## Context

LifeOS uses Git Flow: `dev` is the integration branch, `release/*` and
`hotfix/*` branches are promoted to `main`, and `main` is production-ready.
The previous process still required a maintainer to create a release branch,
edit the Gradle version, tag `main`, publish release notes, and synchronize the
release back to `dev`. Those repeated steps were easy to perform inconsistently
and did not maintain a clean development-version lifecycle.

Version truth must remain singular. The root `build.gradle.kts` is the source
of the project version, while Git tags are the source of the release history.
`main` must remain protected, and no release may be published from a commit
that has not passed CI.

## Decision

Use two workflows:

1. `.github/workflows/prepare-release.yml` starts after a merged `dev` PR with
   exactly one release label (`release:patch`, `release:minor`, or
   `release:major`), or from `workflow_dispatch` with the same bump choice.
   It calculates the next stable SemVer from the latest `vX.Y.Z` tag (the first
   release adopts the version already declared on `dev`), creates
   `release/vX.Y.Z` from current `dev`, updates `build.gradle.kts`, and opens a
   PR into `main`.
2. `.github/workflows/release.yml` listens for a successful CI `workflow_run`
   on an actual push to this repository's `main`. It validates the version and
   release ordering, creates the tag and GitHub Release idempotently, then
   opens a synchronization PR into `dev`. That PR merges the verified `main`
   state and changes the Gradle version to the next `-SNAPSHOT`.

The reusable `scripts/release-version.sh` contains the parsing, comparison,
bump, and single-version-source update logic used by both workflows.

## Options considered

- **Keep every release step manual** — rejected because branch creation,
  version editing, tagging, notes, and sync-back are mechanical and
  idempotent.
- **Automatically merge `dev` into `main`** — rejected because it would
  bypass the production PR guard and remove the review point that Git Flow
  intentionally provides.
- **Infer versions from commit messages** — deferred. Conventional Commits
  are not currently enforced, and an explicit release label makes the
  major/minor/patch decision visible without adding a commit-lint dependency.
- **Release directly on every push to `main`** — rejected because the release
  job could race CI. The `workflow_run` gate ties publication to the verified
  CI result for the exact commit.

## Reliability and security safeguards

- Release preparation only writes when the merged PR originated in this
  repository; fork PRs cannot use the write-capable path.
- The publisher only accepts successful `push` runs from this repository's
  `main`, and uses the triggering commit SHA for checkout and release target.
- A stable version must be greater than the latest stable tag. Reusing a
  version tag on a different commit fails closed.
- Tag creation and GitHub Release creation are separate idempotent checks, so a
  transient failure after tagging can be repaired by a later successful
  workflow run.
- A single concurrency group serializes releases on `main`, preventing two
  verified runs from racing on one version.
- The sync-back PR refuses to use a moving `main` tip that differs from the
  commit CI verified. Merge conflicts outside `build.gradle.kts` fail loudly
  for manual resolution rather than silently discarding development work.
- The workflows require the repository-level **Allow GitHub Actions to create
  and approve pull requests** setting in addition to the workflow permission
  block. `GITHUB_TOKEN`-created PRs may require a maintainer to approve their
  workflow run before CI executes.

## Consequences

Normal release flow is now: label a merged `dev` PR, review and merge the
generated `release/vX.Y.Z` PR into `main`, then let verified CI publish the
release and open the `dev` synchronization PR. A maintainer still approves the
production promotion and any repository-level workflow approval; no protected
branch is bypassed.

The system intentionally does not publish pre-releases. Supporting release
candidates would require defining ordering and promotion semantics beyond the
current Git Flow model.

## Validation

Local validation covers the SemVer helper with shell syntax checks and
representative initial, patch, minor, major, comparison, and file-update
cases. The first real release must still be observed end to end because
GitHub's `workflow_run`, token permissions, and workflow-approval behavior
cannot be fully simulated locally.
