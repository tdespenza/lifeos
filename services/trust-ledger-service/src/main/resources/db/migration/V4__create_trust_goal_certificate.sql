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
    CONSTRAINT uk_trust_goal_certificate_scope_key
        UNIQUE (owner_account_id, tenant_id, idempotency_key_hash),
    CONSTRAINT ck_trust_goal_certificate_version CHECK (goal_version >= 0),
    CONSTRAINT ck_trust_goal_certificate_digest CHECK (achievement_digest_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_trust_goal_certificate_key_hash CHECK (idempotency_key_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_trust_goal_certificate_state CHECK (state IN ('PENDING_EXTERNAL_ANCHOR', 'SUBMITTING', 'CONFIRMED')),
    CONSTRAINT ck_trust_goal_certificate_receipt CHECK (
        (state IN ('PENDING_EXTERNAL_ANCHOR', 'SUBMITTING') AND transaction_hash IS NULL AND block_number IS NULL)
        OR (state = 'CONFIRMED' AND transaction_hash IS NOT NULL AND block_number IS NOT NULL AND block_number >= 0)
    )
);

CREATE INDEX idx_trust_goal_certificate_owner
    ON trust_goal_certificate (owner_account_id, tenant_id, created_at);
