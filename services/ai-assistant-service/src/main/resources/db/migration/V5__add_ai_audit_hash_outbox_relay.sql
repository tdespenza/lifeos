ALTER TABLE ai_audit_hash_outbox_event
    ADD COLUMN attempt_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN lease_token UUID,
    ADD COLUMN lease_until TIMESTAMP WITH TIME ZONE,
    ADD COLUMN dead_lettered_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN last_failure_code VARCHAR(64);

ALTER TABLE ai_audit_hash_outbox_event
    ADD CONSTRAINT ck_ai_audit_hash_outbox_attempts CHECK (attempt_count >= 0);

CREATE INDEX idx_ai_audit_hash_outbox_claimable
    ON ai_audit_hash_outbox_event (next_attempt_at, created_at);

CREATE TABLE ai_audit_hash_outbox_dead_letter (
    outbox_event_id UUID PRIMARY KEY,
    audit_event_id UUID NOT NULL,
    attempt_count INTEGER NOT NULL,
    failure_code VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
