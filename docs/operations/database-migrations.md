# Database migration runbook

## Scope

`identity-service` and `task-goal-service` use Flyway for PostgreSQL schema evolution. Production
Hibernate configuration is `ddl-auto: validate`; application startup therefore verifies the
schema but never changes it. Migration files are immutable, ordered SQL under each service's
`src/main/resources/db/migration/` directory.

The initial controlled migration set is deliberately split:

| Service | Version 1 | Version 2 | Version 3 | Version 4 |
| --- | --- | --- | --- | --- |
| Identity | Baseline for every mapped identity entity | `security_audit_event.outcome_code`, `authorization_membership`, and its scoped lookup index | Nullable session last-use/device metadata | Bounded backfill, validated non-null constraints, and the account cursor index via PostgreSQL `CREATE INDEX CONCURRENTLY` |
| Task/Goal | Baseline `goal` table | Nullable `owner_account_id`, nullable `tenant_id` | Non-transactional PostgreSQL `CREATE INDEX CONCURRENTLY` for `idx_goal_owner_tenant` | — |

Version 2 is an expand-only change. It never invents an owner or tenant for a legacy goal; rows
without those facts remain inaccessible through the fail-closed authorization path.

The Identity V1 baseline preserves the established PostgreSQL OID-backed `@Lob String` storage
for encrypted refresh-replay envelopes. This avoids an unreviewed conversion of existing large
objects when a legacy database is baselined at V1. Automated H2 tests define an OID-as-CLOB
compatibility domain because H2 has no PostgreSQL large-object implementation; that test fixture
does not alter the shipped migration SQL.

## Preflight

1. Take and verify a restorable backup of each service database independently.
2. Run the exact release artifact against a clone or staging database with the intended Flyway
   environment values.
3. Inspect `flyway_schema_history`, migration checksums, free disk, database locks, and the
   absence of a concurrent deployer for each database.
4. Verify the application has the least privilege needed for DDL only during migration. Do not
   put database or workload secrets in command history or deployment logs.
5. Deploy database migrations before application instances that require the new schema, then
   verify readiness and `ddl-auto: validate` startup before increasing traffic.

Fresh empty databases use the defaults: `*_FLYWAY_BASELINE_ON_MIGRATE=false`, so Flyway applies
every version listed above. CI uses explicit H2-equivalent migration locations for Identity and
Task/Goal because H2 does not implement PostgreSQL's `CREATE INDEX CONCURRENTLY`; production uses
the default PostgreSQL vendor migration locations.

## Existing Hibernate-managed databases

Never set `baseline-on-migrate` blindly. First compare the live database to the relevant V1 schema
on a staging clone and choose one of these guarded paths:

| Observed schema | Required environment for the one-time migration | Result |
| --- | --- | --- |
| Existing pre-Story-1.6 schema matches V1 but has no Flyway history | `IDENTITY_FLYWAY_BASELINE_ON_MIGRATE=true`, `IDENTITY_FLYWAY_BASELINE_VERSION=1`; `TASK_GOAL_FLYWAY_BASELINE_ON_MIGRATE=true`, `TASK_GOAL_FLYWAY_BASELINE_VERSION=1` | Identity applies V2, V3, and V4; Task/Goal applies V2 and V3. |
| Identity schema already includes its V2 objects but has no Flyway history | `IDENTITY_FLYWAY_BASELINE_ON_MIGRATE=true`, `IDENTITY_FLYWAY_BASELINE_VERSION=2` | Records Identity V2, then applies the session-management V3 and V4 migrations. |
| Identity schema already includes the session-management V3 columns but has no Flyway history | `IDENTITY_FLYWAY_BASELINE_ON_MIGRATE=true`, `IDENTITY_FLYWAY_BASELINE_VERSION=3` | Records Identity V3, then applies the online session-management V4 migration. |
| Identity schema already includes the session-management V4 constraints/index but has no Flyway history | `IDENTITY_FLYWAY_BASELINE_ON_MIGRATE=true`, `IDENTITY_FLYWAY_BASELINE_VERSION=4` | Records Identity's current version; no historical SQL is reapplied. |
| Task/Goal schema already has V2 columns but needs the online index | `TASK_GOAL_FLYWAY_BASELINE_ON_MIGRATE=true`, `TASK_GOAL_FLYWAY_BASELINE_VERSION=2` | Records V2, then applies Task/Goal V3 only. |
| Task/Goal schema already includes the V3 index but has no Flyway history | `TASK_GOAL_FLYWAY_BASELINE_ON_MIGRATE=true`, `TASK_GOAL_FLYWAY_BASELINE_VERSION=3` | Records Task/Goal's current version; no historical SQL is reapplied. |
| Schema differs from the expected V1/V2/V3/V4 shape | Do not start the application or baseline it. | Reconcile with an explicitly reviewed, forward-only migration first. |

Before recording Task/Goal baseline version 3, verify the complete V3 index contract—not merely
its name. Run this through the Task/Goal Flyway datasource, where `current_schema()` resolves the
active Flyway schema. It must return exactly one row with `v3_contract_valid = true`; otherwise
repair the index first and do not baseline V3:

```sql
WITH expected AS (
    SELECT
        current_schema() AS flyway_schema,
        format(
            'CREATE INDEX idx_goal_owner_tenant ON %I.goal USING btree (owner_account_id, tenant_id)',
            current_schema()
        ) AS v3_index_definition
)
SELECT
    actual.index_schema,
    actual.table_schema,
    actual.indisvalid,
    actual.index_definition,
    expected.v3_index_definition,
    COALESCE(
        actual.indisvalid
            AND actual.index_definition = expected.v3_index_definition,
        false
    ) AS v3_contract_valid
FROM expected
LEFT JOIN LATERAL (
    SELECT
        index_schema.nspname AS index_schema,
        goal_schema.nspname AS table_schema,
        index_state.indisvalid,
        pg_get_indexdef(index_relation.oid) AS index_definition
    FROM pg_class index_relation
    JOIN pg_namespace index_schema ON index_schema.oid = index_relation.relnamespace
    JOIN pg_index index_state ON index_state.indexrelid = index_relation.oid
    JOIN pg_class goal_relation ON goal_relation.oid = index_state.indrelid
    JOIN pg_namespace goal_schema ON goal_schema.oid = goal_relation.relnamespace
    WHERE index_schema.nspname = expected.flyway_schema
      AND index_relation.relname = 'idx_goal_owner_tenant'
      AND goal_schema.nspname = expected.flyway_schema
      AND goal_relation.relname = 'goal'
) actual ON true;
```

Unset the one-time `*_FLYWAY_BASELINE_ON_MIGRATE` switch after history is established. `baseline`
is metadata; it does not alter an existing schema. The post-migration application startup validation
is the final guard against baselining the wrong shape.

Before recording Identity baseline version 4, verify the complete cursor-index contract—not merely
the index name. Run this through the Identity Flyway datasource, where `current_schema()` resolves
the active Flyway schema. It must return exactly one row with `v4_contract_valid = true`; otherwise
repair the index and metadata shape first:

```sql
WITH expected AS (
    SELECT
        current_schema() AS flyway_schema,
        format(
            'CREATE INDEX ix_auth_session_account_cursor ON %I.auth_session USING btree (account_id, last_used_at, created_at, id)',
            current_schema()
        ) AS v4_index_definition
)
SELECT
    actual.index_schema,
    actual.table_schema,
    actual.indisvalid,
    actual.index_definition,
    actual.index_predicate,
    expected.v4_index_definition,
    COALESCE(
        actual.indisvalid
            AND actual.index_definition = expected.v4_index_definition
            AND actual.index_predicate IS NULL,
        false
    ) AS v4_contract_valid
FROM expected
LEFT JOIN LATERAL (
    SELECT
        index_schema.nspname AS index_schema,
        session_schema.nspname AS table_schema,
        index_state.indisvalid,
        pg_get_indexdef(index_relation.oid) AS index_definition,
        pg_get_expr(index_state.indpred, index_state.indrelid) AS index_predicate
    FROM pg_class index_relation
    JOIN pg_namespace index_schema ON index_schema.oid = index_relation.relnamespace
    JOIN pg_index index_state ON index_state.indexrelid = index_relation.oid
    JOIN pg_class session_relation ON session_relation.oid = index_state.indrelid
    JOIN pg_namespace session_schema ON session_schema.oid = session_relation.relnamespace
    WHERE index_schema.nspname = expected.flyway_schema
      AND index_relation.relname = 'ix_auth_session_account_cursor'
      AND session_schema.nspname = expected.flyway_schema
      AND session_relation.relname = 'auth_session'
) actual ON true;
```

## Verification and rollout

For each service, confirm:

- Flyway reports the expected current version and no failed migration.
- The relevant V2 table/columns exist; for Task/Goal V3, the complete-contract query above returns
  exactly one row with `v3_contract_valid = true`.
- Hibernate validation starts successfully.
- Readiness is healthy before traffic moves to the new instances.
- For Task/Goal, legacy null-owner rows remain excluded and newly created goals have both scope
  columns populated.
- For Identity, authorization decisions can read active scoped memberships without a full scan.
- For Identity, `auth_session.last_used_at`, the four safe device metadata columns, and their
  non-null constraints exist; legacy rows contain the documented unknown values.
- For Identity, the complete V4 index-contract query above returns exactly one valid index with
  account scoping, `(last_used_at, created_at, id)` order, and no unexpected predicate.

Use a rolling deployment only after the database migration is complete. V2 remains compatible with
the prior application because its added goal columns are nullable and its audit column and
membership table are additive. Task/Goal V3 runs without an enclosing transaction so PostgreSQL
can build the index concurrently; Flyway uses a session-level advisory lock
(`spring.flyway.postgresql.transactional-lock=false`) to keep concurrent migration deployers out.

## Failure and rollback

Flyway migration scripts are never edited or deleted after a release. If a migration fails before
commit, stop rollout, inspect the failed history row, restore or repair only according to Flyway's
documented procedure, and rerun on a clone before retrying production. Task/Goal V3 is
non-transactional: an interrupted PostgreSQL concurrent index build can leave an invalid index, so
inspect it and perform only a reviewed repair before marking Flyway history repaired or retrying.
Identity V4 is also non-transactional: the PostgreSQL backfill commits each bounded batch before
selecting the next batch, so completed batches remain on interruption and a retry is idempotent.
An interrupted constraint validation or concurrent index build still requires the same inspected,
reviewed repair path before retrying.
The V3 statement intentionally does not use `IF NOT EXISTS`; a leftover invalid index must cause a
visible failure until an operator removes or repairs it deliberately.
If a migration commits but the application fails validation or readiness, stop traffic advancement
and roll application instances back to the prior compatible version.

For a data-impacting or non-compatible future migration, use a new forward-only corrective
migration. Restoring the verified preflight backup is the database rollback path for this initial
release; do not manually drop the new authorization table, columns, or indexes from a live system.
