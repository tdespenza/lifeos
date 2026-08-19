CREATE TABLE vault_document (
    id UUID NOT NULL,
    owner_account_id UUID NOT NULL,
    tenant_id VARCHAR(255) NOT NULL,
    object_reference VARCHAR(160) NOT NULL,
    checksum_sha256 VARCHAR(64) NOT NULL,
    content_length BIGINT NOT NULL,
    content_type VARCHAR(128) NOT NULL,
    title VARCHAR(255) NOT NULL,
    metadata_tags VARCHAR(1024) NOT NULL,
    document_timestamp TIMESTAMP WITH TIME ZONE,
    source VARCHAR(16) NOT NULL,
    classification VARCHAR(16) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT pk_vault_document PRIMARY KEY (id),
    CONSTRAINT uk_vault_document_object_reference UNIQUE (object_reference),
    CONSTRAINT ck_vault_document_length CHECK (content_length > 0),
    CONSTRAINT ck_vault_document_source CHECK (source IN ('UPLOAD', 'SCANNER', 'IMPORT')),
    CONSTRAINT ck_vault_document_classification CHECK (classification IN ('PRIVATE', 'SENSITIVE', 'CONFIDENTIAL')),
    CONSTRAINT ck_vault_document_version CHECK (version >= 0)
);

CREATE INDEX idx_vault_document_owner_tenant_updated
    ON vault_document (owner_account_id, tenant_id, updated_at DESC, id ASC);

CREATE TABLE document_command_idempotency (
    id UUID NOT NULL,
    actor_account_id UUID NOT NULL,
    tenant_id VARCHAR(255) NOT NULL,
    operation VARCHAR(32) NOT NULL,
    document_id UUID NOT NULL,
    idempotency_key_hash VARCHAR(64) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    expected_version BIGINT NOT NULL,
    state VARCHAR(16) NOT NULL,
    response_snapshot TEXT,
    completed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_document_command_idempotency PRIMARY KEY (id),
    CONSTRAINT uk_document_command_idempotency_scope_key UNIQUE (
        actor_account_id, tenant_id, operation, idempotency_key_hash
    ),
    CONSTRAINT ck_document_command_idempotency_operation CHECK (operation IN ('UPLOAD', 'METADATA_UPDATE')),
    CONSTRAINT ck_document_command_idempotency_expected_version CHECK (expected_version >= -1),
    CONSTRAINT ck_document_command_idempotency_state CHECK (
        (state = 'PENDING' AND completed_at IS NULL AND response_snapshot IS NULL)
        OR (state = 'COMPLETED' AND completed_at IS NOT NULL AND response_snapshot IS NOT NULL)
    )
);

CREATE INDEX idx_document_command_idempotency_document
    ON document_command_idempotency (document_id);

CREATE TABLE document_vault_security_audit_event (
    id UUID NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    account_id UUID,
    correlation_id VARCHAR(128) NOT NULL,
    client_fingerprint VARCHAR(64) NOT NULL,
    outcome_code VARCHAR(64) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_document_vault_security_audit_event PRIMARY KEY (id)
);

CREATE INDEX idx_document_vault_security_audit_event_account_time
    ON document_vault_security_audit_event (account_id, occurred_at);
