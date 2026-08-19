CREATE TABLE media_session_artifact (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL UNIQUE,
    owner_account_id UUID NOT NULL,
    tenant_id VARCHAR(255) NOT NULL,
    transcription_mode VARCHAR(32) NOT NULL,
    transcript TEXT NOT NULL,
    summary TEXT NOT NULL,
    action_items_json TEXT NOT NULL,
    processing_state VARCHAR(32) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL,
    CONSTRAINT media_session_artifact_state_valid CHECK (processing_state IN ('READY'))
);

CREATE INDEX idx_media_session_artifact_owner_session
    ON media_session_artifact (tenant_id, owner_account_id, session_id);
