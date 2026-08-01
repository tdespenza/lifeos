# Project Management Conventions

How epics, stories, GitHub Issues, and the project board relate to each other, so tracking stays consistent as the backlog grows.

## Source of truth

Authority is split by concern, not duplicated:

* **`docs/epics.md`** owns *what the work is* — FR/NFR numbering, epic goals, FR coverage, and (once written) acceptance criteria. This is the only place that content is authored; issues and the board never define new requirements, they reference `docs/epics.md`'s.
* **The LifeOS Roadmap project board** ([github.com/users/tdespenza/projects/2](https://github.com/users/tdespenza/projects/2)) owns *how far along the work is* — its Status field (Todo / In Progress / Done) is the single source of truth for progress. See "Project board" below.
* **GitHub Issue bodies** own neither — they're links and descriptive notes (goal summary, FR list, implementation notes) copied from `docs/epics.md` for convenience, plus a prose status line that's informational only. The prose status line is not authoritative; the board's Status field is. If an issue's prose ever disagrees with the board, trust the board.

If `docs/epics.md` and an issue/the board ever disagree about *what the work is* (not how far along it is), `docs/epics.md` wins, and the issue/board should be corrected to match it — not the other way around.

## Issue conventions

* **One GitHub Issue per epic**, titled exactly `Epic N: <Title>` to match `docs/epics.md`, labeled `epic`.
* Epic issue body includes: the epic goal (user-value statement), its FR coverage list, current status, implementation notes/dependencies, and a link back to the relevant section of `docs/epics.md`.
* **Stories**, once written (`docs/epics.md` § `Story N.M`), become their own GitHub Issues titled `Story N.M: <Title>`, labeled `story`, and linked as GitHub sub-issues under their parent epic issue (via GitHub's native Sub-issues feature, or a `- [ ] #123` task-list reference in the epic issue body if sub-issues aren't available).
* Issues that aren't epics or stories (a build failure, a doc typo, a dependency bump) use the existing default label set (`bug`, `documentation`, `enhancement`, etc.) as normal — the `epic`/`story` labels are additive, not a replacement taxonomy.

## Labels

| Label | Meaning |
| --- | --- |
| `epic` | Top-level epic tracking issue |
| `story` | Individual story issue (introduced once stories are written) |

Everything else uses the repo's existing default label set.

## Project board: "LifeOS Roadmap"

* One row per epic/story issue.
* The board's **Status** field (Todo / In Progress / Done) is the single source of truth for progress. Don't duplicate progress via labels, issue titles, or emoji — one place to look, one place that can be wrong.
* Work completed before this tracking system existed (e.g. account registration, goal create/list + dependency-order computation) is marked **In Progress** or **Done** on the board with a note in the issue body pointing at the real code (service, file, or PR) — not silently closed, since closing an epic issue should mean the *whole* epic (all its FRs) is done, and several partially-built epics still have real FRs remaining. Mark only the specific FRs that are actually implemented — don't let one done FR imply a sibling FR is done too (see `docs/epics.md`'s Task & Goal Management epic for what that mistake looks like once caught and fixed).

## Numbering

* FR/NFR numbers in `docs/epics.md` are permanent identifiers once assigned — don't renumber them after stories, commits, or PRs start referencing them. If a requirement turns out to be wrong or redundant, mark it superseded/removed in place rather than reusing its number for something else.
* Epic/story numbers (`Epic N`, `Story N.M`) are stable once their GitHub Issue exists. If an epic is later split or merged, note it explicitly in both the issue and `docs/epics.md` rather than silently reusing a number for something different.

## Keeping this in sync

GitHub Issues and the project board can't literally be part of a git diff, so "same PR" doesn't mean "same commit" here — it means: don't merge a `docs/epics.md` change that affects tracking (a new epic, an FR added to an existing epic, an epic split or merged) without also updating the corresponding GitHub Issue and project board entry *before* that PR merges, in the same work session. Concretely:

* The PR description should link the issue(s) it affects.
* If the PR adds an epic, create its GitHub Issue and add it to the board (with the right Status) before merging — don't leave that for later.
* If the PR changes an existing epic's FR coverage or status, edit that epic's issue body to match before merging.

There's no automated enforcement of this yet (no CI check verifies issue/board state) — it's a discipline the PR author is responsible for, the same way the rest of this repo's documentation conventions are.
