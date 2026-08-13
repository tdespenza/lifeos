-- H2 test equivalent of the PostgreSQL expand migration. Legacy ownership remains unknown.
ALTER TABLE goal ADD COLUMN IF NOT EXISTS owner_account_id UUID;
ALTER TABLE goal ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(255);
