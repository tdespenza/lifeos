# System Design Lab

This lab is a reusable, reviewable set of system-design exercises for the ten mini-systems named
by FR90. It is deliberately documentation-first: none of these designs is a deployed service,
an approved production architecture, or a replacement for a LifeOS product boundary.

Each exercise makes its assumptions, resource limits, consistency choices, recovery paths, and
operational signals explicit. The goal is to practice defending a design under changing load and
failure conditions, rather than to memorize a single technology stack.

## Use

1. Start a new exercise from [the reusable template](template.md).
2. Read the [catalog index](INDEX.md) and choose a system.
3. Change an assumption deliberately, then revisit the partition key, durable state, retry policy,
   and saturation signals.
4. Verify the catalog structure and required sections deterministically:

   ```text
   bash labs/system-design-lab/verify.sh
   ```

The verifier checks local files and headings only. It does not deploy, benchmark, contact a
dependency, or certify a design for production.

## Conventions

- Capacity and latency values are design inputs, not measurements or service-level commitments.
- A write is idempotent only when the durable deduplication key, response/retry behavior, and
  expiration are stated.
- A cache, search index, replica, or analytics view is a derived projection; its source of truth,
  staleness behavior, and rebuild path must be named.
- Privacy-sensitive identifiers and content stay out of logs, metrics labels, and broadly shared
  events. Every exercise specifies a deletion, retention, or minimization boundary.
- "Availability" never means silently accepting a write that cannot be recovered or attributed.

## Contents

The [catalog index](INDEX.md) links the ten exercises and summarizes their central design question.
Every document follows the common headings enforced by `verify.sh`:

1. Requirements
2. API shape
3. Data model
4. Scaling and partitioning
5. Bottlenecks and tradeoffs
6. Failure and recovery
7. Observability
8. Security and privacy
