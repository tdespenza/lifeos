CREATE TABLE media_asset (
    id UUID PRIMARY KEY,
    owner_account_id UUID NOT NULL,
    tenant_id VARCHAR(255) NOT NULL,
    source_object_reference VARCHAR(160) UNIQUE,
    checksum_sha256 VARCHAR(64),
    content_length BIGINT,
    content_type VARCHAR(128),
    title VARCHAR(140) NOT NULL,
    status VARCHAR(48) NOT NULL,
    hls_manifest_reference VARCHAR(160),
    processing_failure_code VARCHAR(80),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL,
    CONSTRAINT media_asset_content_facts_valid CHECK (
        (source_object_reference IS NULL AND checksum_sha256 IS NULL AND content_length IS NULL AND content_type IS NULL)
        OR (source_object_reference IS NOT NULL AND checksum_sha256 IS NOT NULL AND content_length > 0 AND content_type IS NOT NULL)
    ),
    CONSTRAINT media_asset_status_valid CHECK (status IN (
        'AWAITING_UPLOAD', 'STORED_AWAITING_EXTERNAL_PROCESSING', 'HLS_READY', 'PROCESSING_FAILED'
    ))
);

CREATE INDEX idx_media_asset_owner_created ON media_asset (tenant_id, owner_account_id, created_at, id);

CREATE TABLE media_session (
    id UUID PRIMARY KEY,
    owner_account_id UUID NOT NULL,
    tenant_id VARCHAR(255) NOT NULL,
    kind VARCHAR(32) NOT NULL,
    title VARCHAR(140) NOT NULL,
    scheduled_start_at TIMESTAMP WITH TIME ZONE NOT NULL,
    scheduled_end_at TIMESTAMP WITH TIME ZONE NOT NULL,
    time_zone VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL,
    CONSTRAINT media_session_interval_valid CHECK (scheduled_end_at > scheduled_start_at),
    CONSTRAINT media_session_kind_valid CHECK (kind IN ('COACHING', 'JOURNALING')),
    CONSTRAINT media_session_status_valid CHECK (status IN ('SCHEDULED', 'CANCELLED', 'ENDED'))
);

CREATE INDEX idx_media_session_owner_start ON media_session (tenant_id, owner_account_id, scheduled_start_at, id);

CREATE TABLE media_mutation_idempotency (
    id UUID PRIMARY KEY,
    actor_account_id UUID NOT NULL,
    tenant_id VARCHAR(255) NOT NULL,
    operation VARCHAR(48) NOT NULL,
    resource_scope VARCHAR(128) NOT NULL,
    idempotency_key_hash VARCHAR(64) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    expected_version BIGINT,
    state VARCHAR(16) NOT NULL,
    response_status INTEGER,
    response_location VARCHAR(255),
    response_json TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE,
    version BIGINT NOT NULL,
    CONSTRAINT media_mutation_idempotency_state_valid CHECK (state IN ('PENDING', 'COMPLETED')),
    CONSTRAINT uk_media_mutation_idempotency_scope_key UNIQUE (
        actor_account_id, tenant_id, operation, resource_scope, idempotency_key_hash
    )
);

CREATE TABLE media_security_audit_event (
    id UUID PRIMARY KEY,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    actor_account_id UUID,
    session_id UUID,
    action VARCHAR(80) NOT NULL,
    outcome VARCHAR(32) NOT NULL,
    target_type VARCHAR(64) NOT NULL,
    target_id UUID,
    correlation_id VARCHAR(64) NOT NULL,
    client_fingerprint VARCHAR(64),
    reason_code VARCHAR(80)
);

CREATE INDEX idx_media_security_audit_time ON media_security_audit_event (occurred_at);
