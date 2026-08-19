CREATE TABLE trust_digest_anchor_request (
    request_id UUID NOT NULL,
    owner_account_id UUID NOT NULL,
    tenant_id VARCHAR(255) NOT NULL,
    subject_type VARCHAR(48) NOT NULL,
    subject_id UUID NOT NULL,
    subject_version BIGINT NOT NULL,
    digest_sha256 VARCHAR(64) NOT NULL,
    idempotency_key_hash VARCHAR(64) NOT NULL,
    state VARCHAR(32) NOT NULL,
    transaction_hash VARCHAR(66),
    block_number BIGINT,
    last_failure_code VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_trust_digest_anchor_request PRIMARY KEY (request_id),
    CONSTRAINT uk_trust_digest_anchor_scope_key UNIQUE
        (owner_account_id, tenant_id, subject_type, subject_id, subject_version, idempotency_key_hash),
    CONSTRAINT ck_trust_digest_anchor_version CHECK (subject_version >= 0),
    CONSTRAINT ck_trust_digest_anchor_digest CHECK (REGEXP_LIKE(digest_sha256, '^[0-9a-f]{64}$')),
    CONSTRAINT ck_trust_digest_anchor_key CHECK (REGEXP_LIKE(idempotency_key_hash, '^[0-9a-f]{64}$')),
    CONSTRAINT ck_trust_digest_anchor_state CHECK
        (state IN ('PENDING_EXTERNAL_ANCHOR', 'SUBMITTING', 'CONFIRMED', 'FAILED')),
    CONSTRAINT ck_trust_digest_anchor_receipt CHECK (
        (state IN ('PENDING_EXTERNAL_ANCHOR', 'SUBMITTING', 'FAILED')
            AND transaction_hash IS NULL AND block_number IS NULL)
        OR (state = 'CONFIRMED' AND transaction_hash IS NOT NULL AND block_number IS NOT NULL AND block_number >= 0)
    )
);

CREATE INDEX idx_trust_digest_anchor_owner
    ON trust_digest_anchor_request (owner_account_id, tenant_id, created_at);
