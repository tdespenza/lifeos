-- Story 1.7: durable fields required for bounded session listing and safe device metadata.
-- The CREATE guard keeps a partially-baselined installation recoverable; V1 installations take the
-- ALTER path and receive legacy-safe values below.
CREATE TABLE IF NOT EXISTS auth_session (
    id UUID NOT NULL,
    account_id UUID NOT NULL,
    authentication_method VARCHAR(16),
    access_token_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_used_at TIMESTAMP WITH TIME ZONE,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    device_label VARCHAR(64),
    platform VARCHAR(32),
    browser_family VARCHAR(32),
    coarse_location VARCHAR(64),
    revoked BOOLEAN NOT NULL,
    CONSTRAINT pk_auth_session PRIMARY KEY (id)
);

ALTER TABLE auth_session ADD COLUMN IF NOT EXISTS last_used_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE auth_session ADD COLUMN IF NOT EXISTS device_label VARCHAR(64);
ALTER TABLE auth_session ADD COLUMN IF NOT EXISTS platform VARCHAR(32);
ALTER TABLE auth_session ADD COLUMN IF NOT EXISTS browser_family VARCHAR(32);
ALTER TABLE auth_session ADD COLUMN IF NOT EXISTS coarse_location VARCHAR(64);

UPDATE auth_session
SET last_used_at = created_at
WHERE last_used_at IS NULL;
UPDATE auth_session
SET device_label = 'Unknown device'
WHERE device_label IS NULL OR device_label = '';
UPDATE auth_session
SET platform = 'unknown'
WHERE platform IS NULL OR platform = '';
UPDATE auth_session
SET browser_family = 'unknown'
WHERE browser_family IS NULL OR browser_family = '';
UPDATE auth_session
SET coarse_location = 'unknown'
WHERE coarse_location IS NULL OR coarse_location = '';

ALTER TABLE auth_session ALTER COLUMN last_used_at SET NOT NULL;
ALTER TABLE auth_session ALTER COLUMN device_label SET NOT NULL;
ALTER TABLE auth_session ALTER COLUMN platform SET NOT NULL;
ALTER TABLE auth_session ALTER COLUMN browser_family SET NOT NULL;
ALTER TABLE auth_session ALTER COLUMN coarse_location SET NOT NULL;

CREATE INDEX IF NOT EXISTS ix_auth_session_account_cursor
    ON auth_session (account_id, last_used_at, created_at, id);
