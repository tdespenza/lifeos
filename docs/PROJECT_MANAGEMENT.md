# Project Management Conventions

How epics, stories, GitHub Issues, and the project board relate to each other, so tracking stays consistent as the backlog grows.

## Source of truth

`docs/epics.md` is the canonical requirements/epic/story breakdown — it's where FR/NFR numbering, epic goals, FR coverage, and acceptance criteria actually live. GitHub Issues and the **LifeOS Roadmap** project board ([github.com/users/tdespenza/projects/2](https://github.com/users/tdespenza/projects/2)) are the execution-tracking layer derived *from* `docs/epics.md`, not an independent source of truth. If they ever disagree, `docs/epics.md` wins, and the issue/board should be corrected to match it — not the other way around.

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
* Work completed before this tracking system existed (e.g. account registration, task/goal CRUD + dependency ordering) is marked **In Progress** or **Done** on the board with a note in the issue body pointing at the real code (service, file, or PR) — not silently closed, since closing an epic issue should mean the *whole* epic (all its FRs) is done, and several partially-built epics still have real FRs remaining.

## Numbering

* FR/NFR numbers in `docs/epics.md` are permanent identifiers once assigned — don't renumber them after stories, commits, or PRs start referencing them. If a requirement turns out to be wrong or redundant, mark it superseded/removed in place rather than reusing its number for something else.
* Epic/story numbers (`Epic N`, `Story N.M`) are stable once their GitHub Issue exists. If an epic is later split or merged, note it explicitly in both the issue and `docs/epics.md` rather than silently reusing a number for something different.

## Keeping this in sync

When `docs/epics.md` changes in a way that affects tracking (a new epic, an FR added to an existing epic, an epic split or merged), update the corresponding GitHub Issue and project board entry in the *same* PR that changes the doc — don't let the doc and the tracker drift apart across separate changes.
