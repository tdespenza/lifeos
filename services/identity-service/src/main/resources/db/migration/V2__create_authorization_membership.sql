-- Story 1.6: explicit tenant-scoped roles. Personal-tenant MEMBER remains implicit in code.
-- The same release adds a bounded authorization outcome to the pre-existing audit table.
ALTER TABLE security_audit_event ADD COLUMN IF NOT EXISTS outcome_code VARCHAR(64);

CREATE TABLE IF NOT EXISTS authorization_membership (
    id UUID NOT NULL,
    account_id UUID NOT NULL,
    tenant_id VARCHAR(128) NOT NULL,
    role VARCHAR(32) NOT NULL,
    active BOOLEAN NOT NULL,
    CONSTRAINT pk_authorization_membership PRIMARY KEY (id),
    CONSTRAINT uk_authorization_membership_account_tenant_role UNIQUE (account_id, tenant_id, role)
);
CREATE INDEX IF NOT EXISTS idx_authorization_membership_subject_tenant_active
    ON authorization_membership (account_id, tenant_id, active);
