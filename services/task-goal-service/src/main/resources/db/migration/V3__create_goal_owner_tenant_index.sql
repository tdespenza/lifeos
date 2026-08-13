-- PostgreSQL-only online index build. The adjacent Flyway script configuration disables the
-- enclosing transaction, as PostgreSQL forbids CREATE INDEX CONCURRENTLY inside one.
CREATE INDEX CONCURRENTLY idx_goal_owner_tenant
    ON goal (owner_account_id, tenant_id);
