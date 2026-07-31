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

See [README.md](README.md) for the project overview. The full product vision, architecture, and roadmap live in `REQUIREMENTS.md` at the repo root — it's intentionally gitignored (a project-local planning document, not a build input), so a fresh clone won't have it and doesn't need it to build, run, or test the code. Its substance is mirrored across tracked documentation you *do* have: `docs/adr/` (18 architecture decision records), `docs/interview/`, `docs/architecture/current-state.md`, and README.md's own feature/status summary. If you need the source document itself rather than its tracked summaries, that requires the project owner.

## Documentation conventions

* **Architecture Decision Records** live in `docs/adr/`. Per [CLAUDE.md](CLAUDE.md) / [AGENTS.md](AGENTS.md), create or update an ADR whenever a decision affects architecture, data models, scaling strategy, security model, deployment model, external dependencies, API contracts, messaging, persistence, AI providers, or blockchain assumptions.
* Each `docs/` subdirectory (`algorithms/`, `api/`, `architecture/`, `benchmarks/`, `concurrency/`, `diagrams/`, `interview/`) should describe what's *actually built*, not the aspirational target — call out explicitly what's planned-but-not-implemented rather than blending the two. `docs/benchmarks/` in particular must never contain invented numbers; log a benchmark there only once it's actually been run.

## Branching and pull requests

* Branch off the latest `dev`, not off an old already-merged branch — this repo squash-merges every PR, which rewrites history on `dev`. Reusing a stale local branch after a squash-merge causes already-merged files to reappear as "new" in a later PR's diff (a real issue hit during development here). Fetch first, then branch: `git fetch origin dev && git checkout -b your-branch origin/dev` — skipping the fetch can silently branch off a stale local copy of `origin/dev`.
* PRs are squash-merged only (`allow_merge_commit` and `allow_rebase_merge` are disabled repo-wide); branches are auto-deleted on merge.
* `dev` is a protected branch: a PR is required to merge (direct pushes are blocked), but 0 approvals are required, so a solo maintainer isn't locked out.
* Run a code review pass (CodeRabbit reviews automatically; the `code-review` Claude Code skill is also expected to run) before merging a PR, and address real findings before merge rather than after.

## Testing

Every meaningful change should include tests or a documented reason why tests don't apply — see [CLAUDE.md](CLAUDE.md)'s "Implement With Verification" section. Run `./gradlew build` before opening a PR; it compiles and runs the full test suite.
