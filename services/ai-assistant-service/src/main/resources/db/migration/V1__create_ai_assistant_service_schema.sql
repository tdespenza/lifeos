-- AI Assistant owns only conversation metadata and audit-safe decision metadata. Prompt and
-- completion content intentionally have no durable column: this is not long-term memory or RAG.
CREATE TABLE assistant_conversation (
    id UUID NOT NULL,
    owner_account_id UUID NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_assistant_conversation PRIMARY KEY (id),
    CONSTRAINT ck_assistant_conversation_purpose CHECK (
        purpose IN ('GENERAL', 'GOAL_PLANNING', 'FINANCIAL_INSIGHT', 'SESSION_SUMMARY')
    ),
    CONSTRAINT ck_assistant_conversation_status CHECK (status = 'ACTIVE'),
    CONSTRAINT ck_assistant_conversation_version CHECK (version >= 0)
);

CREATE INDEX idx_assistant_conversation_owner_created
    ON assistant_conversation (owner_account_id, created_at DESC, id);

-- Every safety/provider/tool decision is durable without raw prompt/output/bearer/address data.
CREATE TABLE assistant_request_audit_event (
    id UUID NOT NULL,
    conversation_id UUID,
    owner_account_id UUID,
    request_kind VARCHAR(32) NOT NULL,
    outcome VARCHAR(32) NOT NULL,
    prompt_template_id VARCHAR(64) NOT NULL,
    input_fingerprint VARCHAR(64),
    input_characters INTEGER NOT NULL,
    estimated_input_tokens INTEGER NOT NULL,
    requested_output_tokens INTEGER NOT NULL,
    retrieved_context_ids VARCHAR(512) NOT NULL,
    safety_flags VARCHAR(256) NOT NULL,
    provider_id VARCHAR(64) NOT NULL,
    model_name VARCHAR(128) NOT NULL,
    output_summary VARCHAR(64) NOT NULL,
    output_fingerprint VARCHAR(64),
    output_characters INTEGER NOT NULL,
    confidence_score NUMERIC(5, 4),
    tool_operation VARCHAR(64) NOT NULL,
    tool_execution_state VARCHAR(32) NOT NULL,
    latency_millis BIGINT NOT NULL,
    correlation_id VARCHAR(128) NOT NULL,
    client_fingerprint VARCHAR(64) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_assistant_request_audit_event PRIMARY KEY (id),
    CONSTRAINT ck_assistant_request_audit_input_characters CHECK (input_characters >= 0),
    CONSTRAINT ck_assistant_request_audit_input_tokens CHECK (estimated_input_tokens >= 0),
    CONSTRAINT ck_assistant_request_audit_output_tokens CHECK (requested_output_tokens >= 0),
    CONSTRAINT ck_assistant_request_audit_output_characters CHECK (output_characters >= 0),
    CONSTRAINT ck_assistant_request_audit_latency CHECK (latency_millis >= 0),
    CONSTRAINT ck_assistant_request_audit_input_fingerprint CHECK (
        input_fingerprint IS NULL OR input_fingerprint ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_assistant_request_audit_client_fingerprint CHECK (
        client_fingerprint ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_assistant_request_audit_output_fingerprint CHECK (
        output_fingerprint IS NULL OR output_fingerprint ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_assistant_request_audit_confidence CHECK (
        confidence_score IS NULL OR (confidence_score >= 0 AND confidence_score <= 1)
    )
);

CREATE INDEX idx_assistant_request_audit_owner_occurred
    ON assistant_request_audit_event (owner_account_id, occurred_at DESC, id);
CREATE INDEX idx_assistant_request_audit_conversation_occurred
    ON assistant_request_audit_event (conversation_id, occurred_at DESC, id);
