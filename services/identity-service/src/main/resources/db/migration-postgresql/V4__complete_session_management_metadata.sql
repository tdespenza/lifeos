-- PostgreSQL-only online completion for Story 1.7.
-- The adjacent Flyway configuration disables the enclosing transaction so the backfill can commit
-- each bounded batch and the cursor index can be built concurrently without blocking session
-- reads and writes.

DO $$
DECLARE
    updated_rows INTEGER;
BEGIN
    LOOP
        WITH batch AS (
            SELECT id
            FROM auth_session
            WHERE last_used_at IS NULL
               OR device_label IS NULL OR device_label = ''
               OR platform IS NULL OR platform = ''
               OR browser_family IS NULL OR browser_family = ''
               OR coarse_location IS NULL OR coarse_location = ''
            ORDER BY id
            LIMIT 1000
            FOR UPDATE SKIP LOCKED
        )
        UPDATE auth_session AS session
        SET last_used_at = COALESCE(session.last_used_at, session.created_at),
            device_label = CASE
                WHEN session.device_label IS NULL OR session.device_label = ''
                    THEN 'Unknown device'
                ELSE session.device_label
            END,
            platform = CASE
                WHEN session.platform IS NULL OR session.platform = '' THEN 'unknown'
                ELSE session.platform
            END,
            browser_family = CASE
                WHEN session.browser_family IS NULL OR session.browser_family = '' THEN 'unknown'
                ELSE session.browser_family
            END,
            coarse_location = CASE
                WHEN session.coarse_location IS NULL OR session.coarse_location = '' THEN 'unknown'
                ELSE session.coarse_location
            END
        FROM batch
        WHERE session.id = batch.id;

        GET DIAGNOSTICS updated_rows = ROW_COUNT;
        EXIT WHEN updated_rows = 0;
        COMMIT;
    END LOOP;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint constraint_row
        JOIN pg_class table_row ON table_row.oid = constraint_row.conrelid
        WHERE table_row.relname = 'auth_session'
          AND constraint_row.conname = 'ck_auth_session_last_used_at_nn'
    ) THEN
        ALTER TABLE auth_session
            ADD CONSTRAINT ck_auth_session_last_used_at_nn
            CHECK (last_used_at IS NOT NULL) NOT VALID;
    END IF;
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint constraint_row
        JOIN pg_class table_row ON table_row.oid = constraint_row.conrelid
        WHERE table_row.relname = 'auth_session'
          AND constraint_row.conname = 'ck_auth_session_device_label_nn'
    ) THEN
        ALTER TABLE auth_session
            ADD CONSTRAINT ck_auth_session_device_label_nn
            CHECK (device_label IS NOT NULL) NOT VALID;
    END IF;
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint constraint_row
        JOIN pg_class table_row ON table_row.oid = constraint_row.conrelid
        WHERE table_row.relname = 'auth_session'
          AND constraint_row.conname = 'ck_auth_session_platform_nn'
    ) THEN
        ALTER TABLE auth_session
            ADD CONSTRAINT ck_auth_session_platform_nn
            CHECK (platform IS NOT NULL) NOT VALID;
    END IF;
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint constraint_row
        JOIN pg_class table_row ON table_row.oid = constraint_row.conrelid
        WHERE table_row.relname = 'auth_session'
          AND constraint_row.conname = 'ck_auth_session_browser_family_nn'
    ) THEN
        ALTER TABLE auth_session
            ADD CONSTRAINT ck_auth_session_browser_family_nn
            CHECK (browser_family IS NOT NULL) NOT VALID;
    END IF;
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint constraint_row
        JOIN pg_class table_row ON table_row.oid = constraint_row.conrelid
        WHERE table_row.relname = 'auth_session'
          AND constraint_row.conname = 'ck_auth_session_coarse_location_nn'
    ) THEN
        ALTER TABLE auth_session
            ADD CONSTRAINT ck_auth_session_coarse_location_nn
            CHECK (coarse_location IS NOT NULL) NOT VALID;
    END IF;
END $$;

ALTER TABLE auth_session VALIDATE CONSTRAINT ck_auth_session_last_used_at_nn;
ALTER TABLE auth_session VALIDATE CONSTRAINT ck_auth_session_device_label_nn;
ALTER TABLE auth_session VALIDATE CONSTRAINT ck_auth_session_platform_nn;
ALTER TABLE auth_session VALIDATE CONSTRAINT ck_auth_session_browser_family_nn;
ALTER TABLE auth_session VALIDATE CONSTRAINT ck_auth_session_coarse_location_nn;

ALTER TABLE auth_session ALTER COLUMN last_used_at SET NOT NULL;
ALTER TABLE auth_session ALTER COLUMN device_label SET NOT NULL;
ALTER TABLE auth_session ALTER COLUMN platform SET NOT NULL;
ALTER TABLE auth_session ALTER COLUMN browser_family SET NOT NULL;
ALTER TABLE auth_session ALTER COLUMN coarse_location SET NOT NULL;

ALTER TABLE auth_session DROP CONSTRAINT ck_auth_session_last_used_at_nn;
ALTER TABLE auth_session DROP CONSTRAINT ck_auth_session_device_label_nn;
ALTER TABLE auth_session DROP CONSTRAINT ck_auth_session_platform_nn;
ALTER TABLE auth_session DROP CONSTRAINT ck_auth_session_browser_family_nn;
ALTER TABLE auth_session DROP CONSTRAINT ck_auth_session_coarse_location_nn;

DO $$
DECLARE
    existing_definition TEXT;
    existing_valid BOOLEAN;
    expected_definition TEXT := format(
        'CREATE INDEX ix_auth_session_account_cursor ON %I.auth_session USING btree (account_id, last_used_at, created_at, id)',
        current_schema()
    );
BEGIN
    SELECT index_state.indisvalid, pg_get_indexdef(index_relation.oid)
    INTO existing_valid, existing_definition
    FROM pg_class index_relation
    JOIN pg_namespace index_schema ON index_schema.oid = index_relation.relnamespace
    JOIN pg_index index_state ON index_state.indexrelid = index_relation.oid
    JOIN pg_class session_relation ON session_relation.oid = index_state.indrelid
    JOIN pg_namespace session_schema ON session_schema.oid = session_relation.relnamespace
    WHERE index_schema.nspname = current_schema()
      AND index_relation.relname = 'ix_auth_session_account_cursor'
      AND session_schema.nspname = current_schema()
      AND session_relation.relname = 'auth_session';

    IF existing_definition IS NOT NULL
       AND (NOT existing_valid OR existing_definition <> expected_definition) THEN
        RAISE EXCEPTION
            'Existing ix_auth_session_account_cursor does not match the required valid definition';
    END IF;
END $$;

CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_auth_session_account_cursor
    ON auth_session (account_id, last_used_at, created_at, id);
