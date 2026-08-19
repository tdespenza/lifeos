# Requirements and issue traceability audit

The tracked requirements mirror is [`docs/epics.md`](../epics.md). `REQUIREMENTS.md` is intentionally
gitignored in this repository, so a fresh checkout must use the mirror and the source/test evidence
listed by each status line.

## Epic 2 / issue #20

The code and automated tests implement FR1–FR4 and the epic section marks Stories 2.1–2.3 done.
The external [Epic 2 issue](https://github.com/tdespenza/lifeos/issues/20) is stale as of the last
audit: it still says “Not started” and has unchecked task-list entries, while GitHub reports Stories
#136–#138 closed and FR issues #37–#40 open. Closing or editing external issues requires a separate
GitHub-authorized tracking action; this repository does not silently claim that external state has
been synchronized.

## Current status counts (2026-08-19)

The inventory currently records 47/91 functional requirements as done, 22/42 non-functional
requirements as done, and the remainder as partial or pending. Partial statuses are intentional when
they depend on deployment-owned providers, credentials, staging evidence, or product decisions.
The Trust Ledger slices now include the FR52 Media session-summary command and FR68 completed-goal
certificate path: Media and Task/Goal revalidate owner scope through workload-authenticated
projections, Trust Ledger persists owner-scoped idempotent digests, and optional Besu receipt state
is fail-closed. FR52 and FR68 remain partial because production contract deployment, key management,
consent UX, and independent chain verification are not available in this checkout.

Use the following local gates after changing implementation or status evidence:

```bash
./gradlew --no-daemon --no-parallel --max-workers=1 check
bash scripts/verify-architecture.sh
bash scripts/verify-pipeline-scripts.sh
bash scripts/verify-observability-stack.sh
bash scripts/verify-client-foundations.sh
bash scripts/verify-blockchain-foundation.sh
git diff --check
```
