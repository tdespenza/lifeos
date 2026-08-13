-- Story 1.6 expand step. Do not backfill unknown legacy ownership: null legacy rows fail closed.
ALTER TABLE goal ADD COLUMN IF NOT EXISTS owner_account_id UUID;
ALTER TABLE goal ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(255);
CREATE INDEX IF NOT EXISTS idx_goal_owner_tenant ON goal (owner_account_id, tenant_id);
