# Database migration runbook

## Scope

`identity-service`, `task-goal-service`, `document-vault-service`, and `trust-ledger-service` use Flyway for PostgreSQL schema evolution. Production
Hibernate configuration is `ddl-auto: validate`; application startup therefore verifies the
schema but never changes it. Migration files are immutable, ordered SQL under each service's
`src/main/resources/db/migration/` directory.

The initial controlled migration set is deliberately split:

| Service | Version 1 | Version 2 | Version 3 | Version 4 | Version 5 | Version 6 |
| --- | --- | --- | --- | --- | --- | --- |
| Identity | Baseline for every mapped identity entity | `security_audit_event.outcome_code`, `authorization_membership`, and its scoped lookup index | Nullable session last-use/device metadata | Bounded backfill, validated non-null constraints, and the account cursor index via PostgreSQL `CREATE INDEX CONCURRENTLY` | Account-registration idempotency reservation | — |
| Task/Goal | Baseline `goal` table | Nullable `owner_account_id`, nullable `tenant_id` | Non-transactional PostgreSQL `CREATE INDEX CONCURRENTLY` for `idx_goal_owner_tenant` | Durable `goal_create_idempotency` reservations with scope/key uniqueness and a goal lookup index | Goal lifecycle columns/state constraint/version plus `goal_mutation_idempotency` reservations and replay snapshots | Owner-scoped `task`, durable Task command reservations, and persisted Task/Goal dependency/guard tables |
| Document Vault | Metadata, command-idempotency, audit, and opaque-object schema | Privacy-safe content-search token digests | Durable owner-scoped proof requests and `com.lifeos.document.proof.requested.v1` transactional outbox | Leased Kafka relay fields and durable proof-outbox dead-letter table | — | — |
| Trust Ledger | Proof-request projection with checksum/state constraints and owner lookup | — | — | — | — | — |

Version 2 is an expand-only change. It never invents an owner or tenant for a legacy goal; rows
without those facts remain inaccessible through the fail-closed authorization path.

Task/Goal V5 is forward-only and compatible with the V4 create-idempotency contract: it does not
alter `goal_create_idempotency`, its unique scope/key constraint, or its replay behavior. Existing
goals receive `ACTIVE`, version `0`, and an `updated_at` migration timestamp; V5 adds a separate
mutation-reservation table for update, complete, and archive. New lifecycle application instances
must not start until V5 is committed and Hibernate validation succeeds.

Task/Goal V6 is additive: it does not rewrite Goal tables or either Goal idempotency contract. It
adds owner-scoped Task lifecycle/command tables and the persistent polymorphic Task/Goal dependency
graph with a per-owner guard. Deploy V6 before any Task/dependency-aware application instance; its
ordinary transactional DDL either commits all new relations/indexes/constraints or rolls back.

Document Vault V3 is additive and transactional: it adds owner-scoped proof-request plus outbox
tables. V4 adds leased relay state and a durable dead-letter table. Deploy both before exposing the
proof-request routes; the `REQUESTED` state is only an enqueue guarantee and does not imply a Trust
Ledger/Besu anchor. The relay remains disabled unless Kafka and the versioned topic/ACL are supplied.

Trust Ledger V1 is additive and owns only the privacy-minimized proof-request projection consumed
from `lifeos.document.proof.requested.v1`. It stores the request/event UUID, document UUID/version,
owner/tenant scope, checksum, receipt time, and `PENDING_EXTERNAL_ANCHOR`; it has no content or
object-store columns. Deploy V1 before enabling `TRUST_LEDGER_KAFKA_ENABLED`, and provision the
matching `.DLT` topic. V1 does not create or report a blockchain anchor.

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
| Existing pre-Story-1.6 schema matches V1 but has no Flyway history | `IDENTITY_FLYWAY_BASELINE_ON_MIGRATE=true`, `IDENTITY_FLYWAY_BASELINE_VERSION=1`; `TASK_GOAL_FLYWAY_BASELINE_ON_MIGRATE=true`, `TASK_GOAL_FLYWAY_BASELINE_VERSION=1` | Identity applies V2–V5; Task/Goal applies V2–V6. |
| Identity schema already includes its V2 objects but has no Flyway history | `IDENTITY_FLYWAY_BASELINE_ON_MIGRATE=true`, `IDENTITY_FLYWAY_BASELINE_VERSION=2` | Records Identity V2, then applies V3–V5. |
| Identity schema already includes the session-management V3 columns but has no Flyway history | `IDENTITY_FLYWAY_BASELINE_ON_MIGRATE=true`, `IDENTITY_FLYWAY_BASELINE_VERSION=3` | Records Identity V3, then applies V4–V5. |
| Identity schema already includes the session-management V4 constraints/index but has no Flyway history | `IDENTITY_FLYWAY_BASELINE_ON_MIGRATE=true`, `IDENTITY_FLYWAY_BASELINE_VERSION=4` | Records Identity V4, then applies its registration-idempotency V5 migration. |
| Identity schema already includes its V5 registration reservation table and contract | `IDENTITY_FLYWAY_BASELINE_ON_MIGRATE=true`, `IDENTITY_FLYWAY_BASELINE_VERSION=5` | Records Identity's current version; no historical SQL is reapplied. |
| Task/Goal schema already has V2 columns but needs the online index | `TASK_GOAL_FLYWAY_BASELINE_ON_MIGRATE=true`, `TASK_GOAL_FLYWAY_BASELINE_VERSION=2` | Records V2, then applies Task/Goal V3–V6. |
| Task/Goal schema already includes the V3 index but has no Flyway history | `TASK_GOAL_FLYWAY_BASELINE_ON_MIGRATE=true`, `TASK_GOAL_FLYWAY_BASELINE_VERSION=3` | Records V3, then applies the idempotency/lifecycle/Task graph V4–V6 migrations. |
| Task/Goal schema already includes the V4 create-idempotency table, unique scope/key constraint, and goal lookup index but has no Flyway history | `TASK_GOAL_FLYWAY_BASELINE_ON_MIGRATE=true`, `TASK_GOAL_FLYWAY_BASELINE_VERSION=4` | Records V4, then applies lifecycle V5 and Task/graph V6. |
| Task/Goal schema already includes the V5 lifecycle columns, state check, mutation-idempotency table, constraints, and goal lookup index | `TASK_GOAL_FLYWAY_BASELINE_ON_MIGRATE=true`, `TASK_GOAL_FLYWAY_BASELINE_VERSION=5` | Records V5, then applies Task/graph V6. |
| Task/Goal schema already includes V6 Task lifecycle/command and dependency/guard tables and indexes | `TASK_GOAL_FLYWAY_BASELINE_ON_MIGRATE=true`, `TASK_GOAL_FLYWAY_BASELINE_VERSION=6` | Records V6, then applies the additive planning V7 migration. |
| Task/Goal schema already includes V7 planning tables and indexes | `TASK_GOAL_FLYWAY_BASELINE_ON_MIGRATE=true`, `TASK_GOAL_FLYWAY_BASELINE_VERSION=7` | Records Task/Goal's current version; no historical SQL is reapplied. |
| Schema differs from the expected V1/V2/V3/V4/V5/V6/V7 shape | Do not start the application or baseline it. | Reconcile with an explicitly reviewed, forward-only migration first. |

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

Before recording Task/Goal baseline version 4, verify that the durable idempotency reservation
contract is complete. Run this through the Task/Goal Flyway datasource. The query verifies the
table's exact columns, PostgreSQL types, and nullability; the ordered scope/key unique constraint;
the immediate primary-key and scope/key constraints; and the single-key B-tree `goal_id` lookup
index. `v4_contract_valid` must be `true`; otherwise apply or repair the reviewed V4 migration
instead of baselining it:

```sql
WITH reservation_relation AS (
    SELECT relation.oid
    FROM pg_class relation
    JOIN pg_namespace relation_schema ON relation_schema.oid = relation.relnamespace
    WHERE relation_schema.nspname = current_schema()
      AND relation.relname = 'goal_create_idempotency'
      AND relation.relkind = 'r'
), expected_columns(column_name, rendered_type, required_not_null) AS (
    VALUES
        ('id', 'uuid', true),
        ('owner_account_id', 'uuid', true),
        ('tenant_id', 'character varying(255)', true),
        ('idempotency_key_hash', 'character varying(64)', true),
        ('request_fingerprint', 'character varying(64)', true),
        ('goal_id', 'uuid', true),
        ('state', 'character varying(16)', true),
        ('created_at', 'timestamp with time zone', true),
        ('completed_at', 'timestamp with time zone', false)
), checks AS (
    SELECT
        EXISTS (SELECT 1 FROM reservation_relation) AS table_exists,
        EXISTS (
            SELECT 1
            FROM reservation_relation relation
            WHERE (SELECT count(*)
                   FROM pg_attribute column_attribute
                   WHERE column_attribute.attrelid = relation.oid
                     AND column_attribute.attnum > 0
                     AND NOT column_attribute.attisdropped) = 9
              AND NOT EXISTS (
                  SELECT 1
                  FROM expected_columns expected
                  LEFT JOIN pg_attribute column_attribute
                    ON column_attribute.attrelid = relation.oid
                   AND column_attribute.attname = expected.column_name
                   AND column_attribute.attnum > 0
                   AND NOT column_attribute.attisdropped
                  WHERE column_attribute.attnum IS NULL
                     OR format_type(column_attribute.atttypid, column_attribute.atttypmod)
                            <> expected.rendered_type
                     OR column_attribute.attnotnull IS DISTINCT FROM expected.required_not_null
              )
        ) AS columns_valid,
        EXISTS (
            SELECT 1
            FROM reservation_relation relation
            JOIN pg_constraint primary_key_constraint
              ON primary_key_constraint.conrelid = relation.oid
            WHERE primary_key_constraint.conname = 'pk_goal_create_idempotency'
              AND primary_key_constraint.contype = 'p'
              AND NOT primary_key_constraint.condeferrable
              AND NOT primary_key_constraint.condeferred
              AND primary_key_constraint.conkey = ARRAY[
                  (SELECT column_attribute.attnum
                   FROM pg_attribute column_attribute
                   WHERE column_attribute.attrelid = relation.oid
                     AND column_attribute.attname = 'id')
              ]
        ) AS primary_key_valid,
        EXISTS (
            SELECT 1
            FROM reservation_relation relation
            JOIN pg_constraint scope_key_constraint
              ON scope_key_constraint.conrelid = relation.oid
            WHERE scope_key_constraint.conname = 'uk_goal_create_idempotency_scope_key'
              AND scope_key_constraint.contype = 'u'
              AND NOT scope_key_constraint.condeferrable
              AND NOT scope_key_constraint.condeferred
              AND scope_key_constraint.conkey = ARRAY[
                  (SELECT column_attribute.attnum
                   FROM pg_attribute column_attribute
                   WHERE column_attribute.attrelid = relation.oid
                     AND column_attribute.attname = 'owner_account_id'),
                  (SELECT column_attribute.attnum
                   FROM pg_attribute column_attribute
                   WHERE column_attribute.attrelid = relation.oid
                     AND column_attribute.attname = 'tenant_id'),
                  (SELECT column_attribute.attnum
                   FROM pg_attribute column_attribute
                   WHERE column_attribute.attrelid = relation.oid
                     AND column_attribute.attname = 'idempotency_key_hash')
              ]
        ) AS scope_key_constraint_valid,
        EXISTS (
            SELECT 1
            FROM reservation_relation relation
            JOIN pg_index index_state ON index_state.indrelid = relation.oid
            JOIN pg_class goal_index ON goal_index.oid = index_state.indexrelid
            JOIN pg_am goal_index_method ON goal_index_method.oid = goal_index.relam
            WHERE goal_index.relname = 'idx_goal_create_idempotency_goal'
              AND goal_index.relnamespace = (SELECT relation_schema.oid
                                             FROM pg_namespace relation_schema
                                             WHERE relation_schema.nspname = current_schema())
              AND index_state.indisvalid
              AND index_state.indisready
              AND NOT index_state.indisunique
              AND goal_index_method.amname = 'btree'
              AND index_state.indnkeyatts = 1
              AND index_state.indnatts = 1
              AND index_state.indpred IS NULL
              AND index_state.indkey::text = (
                  SELECT column_attribute.attnum::text
                  FROM pg_attribute column_attribute
                  WHERE column_attribute.attrelid = relation.oid
                    AND column_attribute.attname = 'goal_id'
              )
        ) AS goal_lookup_index_valid
)
SELECT
    checks.*,
    table_exists
        AND columns_valid
        AND primary_key_valid
        AND scope_key_constraint_valid
        AND goal_lookup_index_valid AS v4_contract_valid
FROM checks;
```

Before recording Task/Goal baseline version 5, first verify the complete V4 contract above; V5
does not replace it. Then verify that `goal` has non-null `updated_at`, `status`, and `version`
columns; nullable `completed_at` and `archived_at`; and a `ck_goal_lifecycle_state` check that permits
only active rows with no lifecycle timestamps, completed rows with a completion timestamp and no
archive timestamp, and archived rows with an archive timestamp. Verify `goal_mutation_idempotency`
has its 18 V5 columns, immediate primary key `pk_goal_mutation_idempotency`, ordered unique constraint
`uk_goal_mutation_idempotency_scope_key` on `(actor_account_id, tenant_id, goal_id, operation,
idempotency_key_hash)`, operation/version/state and snapshot-lifecycle checks, and the non-unique B-tree
`idx_goal_mutation_idempotency_goal` on `goal_id`. Do not baseline V5 from a partial expand: baseline
V4 instead and let reviewed V5 SQL finish the upgrade.

Before recording Task/Goal baseline version 6, first verify the complete V5 contract above. Verify
that `task` has immutable owner/tenant fields, lifecycle timestamps, version and
`ck_task_lifecycle_state`; `task_command_idempotency` has its scoped unique reservation key and
Task lookup index; and `task_goal_dependency` plus `task_goal_dependency_guard` have their unique
scope/edge constraints and predecessor/dependent indexes. Do not baseline V6 from a partial
deployment: baseline V5 and let reviewed V6 SQL complete it.

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
), metadata_contract AS (
    SELECT
        count(*) = 5 AS all_metadata_columns,
        COALESCE(bool_and(column_attribute.attnotnull), false) AS all_metadata_non_null
    FROM pg_attribute column_attribute
    JOIN pg_class session_relation ON session_relation.oid = column_attribute.attrelid
    JOIN pg_namespace session_schema ON session_schema.oid = session_relation.relnamespace
    WHERE session_schema.nspname = current_schema()
      AND session_relation.relname = 'auth_session'
      AND column_attribute.attnum > 0
      AND NOT column_attribute.attisdropped
      AND column_attribute.attname IN (
          'last_used_at', 'device_label', 'platform', 'browser_family', 'coarse_location'
      )
)
SELECT
    actual.index_schema,
    actual.table_schema,
    actual.indisvalid,
    actual.index_definition,
    actual.index_predicate,
    expected.v4_index_definition,
    metadata_contract.all_metadata_columns,
    metadata_contract.all_metadata_non_null,
    COALESCE(
        actual.indisvalid
            AND actual.index_definition = expected.v4_index_definition
            AND actual.index_predicate IS NULL,
        false
    )
        AND metadata_contract.all_metadata_columns
        AND metadata_contract.all_metadata_non_null AS v4_contract_valid
FROM expected
CROSS JOIN metadata_contract
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

- Flyway reports the expected current version (V6 for Task/Goal) and no failed migration.
- The relevant V2 table/columns exist; for Task/Goal V3, the complete-contract query above returns
  exactly one row with `v3_contract_valid = true`, and its V4 idempotency table, unique scope/key
  constraint, and goal lookup index pass the V4 query above. For Task/Goal V5, the lifecycle columns,
  state check, mutation-reservation table, scope/key unique constraint, and goal lookup index match
  the V5 baseline contract above. For Task/Goal V6, Task lifecycle/command tables and the
  dependency/guard graph contract match the V6 baseline contract above.
- Hibernate validation starts successfully.
- Readiness is healthy before traffic moves to the new instances.
- For Task/Goal, legacy null-owner rows remain excluded and newly created goals have both scope
  columns populated.
- For Task/Goal, matching `POST /api/v1/goals` retries return one goal, while a same-scope key
  paired with a different payload returns `409` without creating another goal.
- For Task/Goal, an `ACTIVE` goal can be versioned-updated, completed, or archived; a completed goal
  can be archived; invalid transitions are rejected; and a matching lifecycle retry returns its
  first committed response snapshot without another version increment.
- For Task/Goal V6, matching Task create retries converge on one ID, lifecycle commands require
  a strong ETag, and concurrent opposite dependency edges cannot both commit a cycle.
- For Identity, authorization decisions can read active scoped memberships without a full scan.
- For Identity, `auth_session.last_used_at`, the four safe device metadata columns, and their
  non-null constraints exist; legacy rows contain the documented unknown values.
- For Identity, the complete V4 index-contract query above returns exactly one valid index with
  account scoping, `(last_used_at, created_at, id)` order, and no unexpected predicate.

Use a rolling deployment only after the database migration is complete. V2 remains compatible with
the prior application because its added goal columns are nullable and its audit column and
membership table are additive. Task/Goal V4 is additive, and V5 preserves the V4 create-reservation
table while adding defaulted lifecycle columns and a separate mutation-reservation table; V5 must
complete before any lifecycle-aware application instance starts. V5 alters `goal` and adds a check,
so measure lock time against a production-sized clone and schedule the migration in the deployment
window. V6 is additive but still must complete before Task/dependency-aware application instances
start, because Hibernate validates the new mapped tables. Task/Goal V3 runs without an enclosing transaction so PostgreSQL can build the index
concurrently; Flyway uses a session-level advisory lock
(`spring.flyway.postgresql.transactional-lock=false`) to keep concurrent migration deployers out.

## Failure and rollback

Flyway migration scripts are never edited or deleted after a release. If a migration fails before
commit, stop rollout, inspect the failed history row, restore or repair only according to Flyway's
documented procedure, and rerun on a clone before retrying production. Task/Goal V3 is
non-transactional: an interrupted PostgreSQL concurrent index build can leave an invalid index, so
inspect it and perform only a reviewed repair before marking Flyway history repaired or retrying.
Task/Goal V4 uses ordinary transactional DDL for an initially empty additive table and index, so a
failed V4 transaction rolls back as a unit; still inspect the Flyway history before retrying.
Task/Goal V5 also uses ordinary transactional DDL; a failed V5 transaction rolls back its lifecycle
columns, state check, mutation-reservation table, and index as a unit, but operators must still
inspect locks and Flyway history before retrying.
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
