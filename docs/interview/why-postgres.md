# Why PostgreSQL?

Today the stateful PostgreSQL footprint is Identity (`lifeos_identity`), Task/Goal
(`lifeos_task_goal`), Profile (`lifeos_profile`), Notification (`lifeos_notification`), Calendar
(`lifeos_calendar`), and Finance (`lifeos_finance`), each behind its own Spring Data
JPA/Hibernate service boundary. There is no shared database or cross-service join. Trust Ledger is
stateless in its current proof-only form.

The core argument is that Postgres gives strong local consistency boundaries. Finance already uses
immutable integer-minor-unit postings, atomic category-correction history, durable idempotency
snapshots, and a PostgreSQL exclusion constraint to prevent concurrent overlapping budgets. That
does not make it a bank or a double-entry payment ledger; it is a user-entered personal-finance
scope. I would rather lean on a database for these local invariants than reimplement them in every
service that touches money or permissions.

I also picked it because of what it doesn't force me into. JSONB with GIN indexes means semi-structured fields — task metadata, calendar recurrence rules — can live alongside strict foreign-key constraints on the fields that actually matter, like `user_id` or `goal_id`. So I'm not stuck choosing between "everything is a rigid column" and "everything is a schemaless blob." I considered MySQL, but its JSON support and recursive/window-function story are weaker for the kind of goal-hierarchy and permission-graph queries this platform needs. I considered CockroachDB/YugabyteDB too, but distributed SQL solves a horizontal-write-scaling problem I don't have yet — each service here is a single-writer bounded context, so consensus overhead would be premature distribution, not a benefit.

The honest tradeoff, and one I am already living with across these stateful services, is that there
is no SQL join across service boundaries. A dashboard combining goals, calendar, and finance needs
API composition or a read model, not a query. That's an accepted cost, not an oversight.

Relevant ADRs: [ADR-008](../adr/ADR-008-use-postgresql-as-system-of-record.md)
