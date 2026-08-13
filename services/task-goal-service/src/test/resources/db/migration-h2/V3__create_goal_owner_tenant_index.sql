-- H2 has no CREATE INDEX CONCURRENTLY syntax. This test-only equivalent verifies the resulting
-- index shape while production executes the PostgreSQL online-index migration.
CREATE INDEX idx_goal_owner_tenant
    ON goal (owner_account_id, tenant_id);
