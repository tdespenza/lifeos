ALTER TABLE ai_audit_hash_outbox_event
    ADD attempt_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE ai_audit_hash_outbox_event
    ADD next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE ai_audit_hash_outbox_event ADD lease_token UUID;
ALTER TABLE ai_audit_hash_outbox_event ADD lease_until TIMESTAMP WITH TIME ZONE;
ALTER TABLE ai_audit_hash_outbox_event ADD dead_lettered_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE ai_audit_hash_outbox_event ADD last_failure_code VARCHAR(64);
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
