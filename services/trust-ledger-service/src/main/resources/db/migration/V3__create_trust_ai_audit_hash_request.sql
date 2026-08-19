CREATE TABLE trust_ai_audit_hash_request (
    audit_event_id UUID PRIMARY KEY,
    owner_account_id UUID,
    conversation_id UUID,
    audit_hash_sha256 VARCHAR(64) NOT NULL CHECK (audit_hash_sha256 ~ '^[0-9a-f]{64}$'),
    state VARCHAR(32) NOT NULL CHECK (state = 'PENDING_EXTERNAL_ANCHOR'),
    received_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_trust_ai_audit_hash_owner
    ON trust_ai_audit_hash_request (owner_account_id, received_at);
