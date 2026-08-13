-- H2 test equivalent of the PostgreSQL online completion migration.
UPDATE auth_session
SET last_used_at = COALESCE(last_used_at, created_at),
    device_label = CASE WHEN device_label IS NULL OR device_label = ''
        THEN 'Unknown device' ELSE device_label END,
    platform = CASE WHEN platform IS NULL OR platform = ''
        THEN 'unknown' ELSE platform END,
    browser_family = CASE WHEN browser_family IS NULL OR browser_family = ''
        THEN 'unknown' ELSE browser_family END,
    coarse_location = CASE WHEN coarse_location IS NULL OR coarse_location = ''
        THEN 'unknown' ELSE coarse_location END;

ALTER TABLE auth_session ALTER COLUMN last_used_at SET NOT NULL;
ALTER TABLE auth_session ALTER COLUMN device_label SET NOT NULL;
ALTER TABLE auth_session ALTER COLUMN platform SET NOT NULL;
ALTER TABLE auth_session ALTER COLUMN browser_family SET NOT NULL;
ALTER TABLE auth_session ALTER COLUMN coarse_location SET NOT NULL;

CREATE INDEX IF NOT EXISTS ix_auth_session_account_cursor
    ON auth_session (account_id, last_used_at, created_at, id);
