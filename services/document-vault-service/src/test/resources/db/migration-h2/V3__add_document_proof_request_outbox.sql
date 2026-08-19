CREATE TABLE document_proof_request (
    id UUID NOT NULL,
    document_id UUID NOT NULL,
    owner_account_id UUID NOT NULL,
    tenant_id VARCHAR(255) NOT NULL,
    document_version BIGINT NOT NULL,
    checksum_sha256 VARCHAR(64) NOT NULL,
    idempotency_key_hash VARCHAR(64) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    state VARCHAR(16) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_document_proof_request PRIMARY KEY (id),
    CONSTRAINT uk_document_proof_request_scope_key UNIQUE (owner_account_id, tenant_id, idempotency_key_hash),
    CONSTRAINT ck_document_proof_request_version CHECK (document_version >= 0),
    CONSTRAINT ck_document_proof_request_checksum CHECK (checksum_sha256 REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT ck_document_proof_request_state CHECK (state = 'REQUESTED')
);

CREATE INDEX idx_document_proof_request_document ON document_proof_request (document_id);

CREATE TABLE document_proof_outbox_event (
    id UUID NOT NULL,
    proof_request_id UUID NOT NULL,
    document_id UUID NOT NULL,
    owner_account_id UUID NOT NULL,
    tenant_id VARCHAR(255) NOT NULL,
    document_version BIGINT NOT NULL,
    checksum_sha256 VARCHAR(64) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_document_proof_outbox_event PRIMARY KEY (id),
    CONSTRAINT uk_document_proof_outbox_request UNIQUE (proof_request_id),
    CONSTRAINT ck_document_proof_outbox_event_type CHECK (event_type = 'document.proof.requested.v1'),
    CONSTRAINT ck_document_proof_outbox_version CHECK (document_version >= 0),
    CONSTRAINT ck_document_proof_outbox_checksum CHECK (checksum_sha256 REGEXP '^[0-9a-f]{64}$')
);

CREATE INDEX idx_document_proof_outbox_created ON document_proof_outbox_event (created_at, id);
