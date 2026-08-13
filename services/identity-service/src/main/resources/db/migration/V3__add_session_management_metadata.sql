-- Story 1.7 expand phase. New session metadata remains nullable until the PostgreSQL online
-- completion migration validates the backfill and enforces the final constraints.
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
