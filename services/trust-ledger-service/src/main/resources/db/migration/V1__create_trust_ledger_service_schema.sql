CREATE TABLE trust_document_proof_request (
    request_id UUID PRIMARY KEY,
    document_id UUID NOT NULL,
    owner_account_id UUID NOT NULL,
    tenant_id VARCHAR(255) NOT NULL,
    document_version BIGINT NOT NULL CHECK (document_version >= 0),
    checksum_sha256 VARCHAR(64) NOT NULL CHECK (checksum_sha256 ~ '^[0-9a-f]{64}$'),
    state VARCHAR(32) NOT NULL CHECK (state IN ('PENDING_EXTERNAL_ANCHOR')),
    received_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_trust_document_proof_owner
    ON trust_document_proof_request (owner_account_id, tenant_id);
