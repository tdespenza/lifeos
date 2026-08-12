# Contributing to LifeOS

## Requirements

To build and run this project locally you need:

* **JDK 25** — the project targets a Java 25 toolchain (see [ADR-001](docs/adr/ADR-001-use-java-25.md)). [SDKMAN](https://sdkman.io/) is the easiest way to install one (e.g. Temurin 25): `sdk install java 25.0.3-tem`.
  * Gradle itself needs JVM **17+** just to launch — if your default `java` on `PATH` is older, either `sdk default java 25.0.3-tem` or set `JAVA_HOME` to a 17+ JDK before running `./gradlew`.
* **Docker** — for local PostgreSQL + Redis via `infrastructure/docker-compose/`.
* **Git**.

No local Gradle install is required — use the wrapper (`./gradlew`), which pins the exact Gradle version the project builds with.

## Getting started

```bash
# start local Postgres + Redis
docker compose -f infrastructure/docker-compose/docker-compose.yml up -d

# build and run all tests
./gradlew build

# run a single service
./gradlew :services:identity-service:bootRun
./gradlew :services:task-goal-service:bootRun
```

See [README.md](README.md) for the project overview. The full product vision, architecture, and roadmap live in `REQUIREMENTS.md` at the repo root — it's intentionally gitignored (a project-local planning document, not a build input), so a fresh clone won't have it and doesn't need it to build, run, or test the code. Its substance is mirrored across tracked documentation you *do* have: `docs/adr/` (19 architecture decision records), `docs/interview/`, `docs/architecture/current-state.md`, `docs/epics.md`, and README.md's own feature/status summary. If you need the source document itself rather than its tracked summaries, that requires the project owner.

## Documentation conventions

* **Architecture Decision Records** live in `docs/adr/`. Per [CLAUDE.md](CLAUDE.md) / [AGENTS.md](AGENTS.md), create or update an ADR whenever a decision affects architecture, data models, scaling strategy, security model, deployment model, external dependencies, API contracts, messaging, persistence, AI providers, or blockchain assumptions.
* Each `docs/` subdirectory (`algorithms/`, `api/`, `architecture/`, `benchmarks/`, `concurrency/`, `diagrams/`, `interview/`) should describe what's *actually built*, not the aspirational target — call out explicitly what's planned-but-not-implemented rather than blending the two. `docs/benchmarks/` in particular must never contain invented numbers; log a benchmark there only once it's actually been run.
* **Epics and stories** live in `docs/epics.md`, decomposing `REQUIREMENTS.md`/ADRs into FRs, NFRs, epics, stories, acceptance criteria, and traceability maps. See [`docs/PROJECT_MANAGEMENT.md`](docs/PROJECT_MANAGEMENT.md) for how that document relates to GitHub Issues and the project board — `docs/epics.md` is the source of truth for requirements and FR coverage; the LifeOS Roadmap board is the source of truth for progress; issues reference both rather than owning either.

## Branching model

This repo follows [Git Flow](https://nvie.com/posts/a-successful-git-branching-model/), using two long-lived branches and three supporting branch types:

* **`main`** — production-ready code only; every commit here should correspond to a release. Protected: PR required, no force-pushes, no deletion.
* **`dev`** — the integration branch (Git Flow's "develop"). This is the repo's default branch — day-to-day work targets `dev`, not `main`. Protected the same way as `main`.
* **`feature/*`** — new work. Branch from `dev`, PR back into `dev`.
* **`release/*`** — release preparation. The [Prepare Release workflow](.github/workflows/prepare-release.yml) creates this branch from `dev`, calculates the next SemVer, updates the root `build.gradle.kts`, and opens the PR into `main`.
* **`hotfix/*`** — urgent fixes to production. Branch from `main`, PR into `main`, same version-bump convention as `release/*`.

Both `main` and `dev` require 0 approvals to merge (so a solo maintainer isn't locked out), but every PR still goes through review — see below.

### Release automation

To request a normal release, add exactly one of `release:patch`, `release:minor`, or `release:major` to the PR being merged into `dev`. After the merge, [Prepare Release](.github/workflows/prepare-release.yml) calculates the next version from the latest release tag, creates `release/vX.Y.Z`, updates `build.gradle.kts`, and opens the release PR into `main`. The same workflow can be started manually with a bump choice from the Actions tab.

Merging a release or hotfix PR into `main` triggers [`.github/workflows/release.yml`](.github/workflows/release.yml) once [CI](.github/workflows/ci.yml) finishes on that exact commit — release automation never runs off an unverified build. It validates that the version is stable SemVer and newer than the latest release, creates or repairs the `vX.Y.Z` tag and GitHub Release idempotently, then opens a synchronization PR into `dev` that advances the development version to the next `-SNAPSHOT`. The synchronization PR is needed because this repo squash-merges every PR; it is not a fast-forward.

The workflows need the repository setting **Allow GitHub Actions to create and approve pull requests** enabled under Settings → Actions → General. PRs created by the default `GITHUB_TOKEN` can also start in GitHub's approval-required state; approve the workflow run in the PR before relying on its CI result. Main remains PR-protected throughout this process — automation prepares and validates the release, but does not bypass the production branch guard.

## Branching and pull requests

* Branch off the latest `dev` (or `main`, for a `hotfix/*`), not off an old already-merged branch — this repo squash-merges every PR, which rewrites history on the target branch. Reusing a stale local branch after a squash-merge causes already-merged files to reappear as "new" in a later PR's diff (a real issue hit during development here). Fetch first, then branch: `git fetch origin dev && git checkout -b feature/your-branch origin/dev` — skipping the fetch can silently branch off a stale local copy.
* PRs are squash-merged only (`allow_merge_commit` and `allow_rebase_merge` are disabled repo-wide); branches are auto-deleted on merge.
* Run a code review pass (CodeRabbit reviews automatically; the `code-review` Claude Code skill is also expected to run) before merging a PR, and address real findings before merge rather than after.

## Testing

Every meaningful change should include tests or a documented reason why tests don't apply — see [CLAUDE.md](CLAUDE.md)'s "Implement With Verification" section. Run `./gradlew build` before opening a PR; it compiles and runs the full test suite.
