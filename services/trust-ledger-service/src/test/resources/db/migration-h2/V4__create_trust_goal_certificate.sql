CREATE TABLE trust_goal_certificate (
    certificate_id UUID NOT NULL,
    goal_id UUID NOT NULL,
    owner_account_id UUID NOT NULL,
    tenant_id VARCHAR(255) NOT NULL,
    goal_version BIGINT NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    achievement_digest_sha256 VARCHAR(64) NOT NULL,
    idempotency_key_hash VARCHAR(64) NOT NULL,
    state VARCHAR(32) NOT NULL,
    transaction_hash VARCHAR(66),
    block_number BIGINT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_trust_goal_certificate PRIMARY KEY (certificate_id),
    CONSTRAINT uk_trust_goal_certificate_scope_key UNIQUE (owner_account_id, tenant_id, idempotency_key_hash),
    CONSTRAINT ck_trust_goal_certificate_version CHECK (goal_version >= 0),
    CONSTRAINT ck_trust_goal_certificate_state CHECK (state IN ('PENDING_EXTERNAL_ANCHOR', 'SUBMITTING', 'CONFIRMED'))
);

CREATE INDEX idx_trust_goal_certificate_owner
    ON trust_goal_certificate (owner_account_id, tenant_id, created_at);
