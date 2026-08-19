-- Durable handoff for a future Trust Ledger producer. This event contains only the commitment;
-- it does not claim publication or blockchain anchoring.
CREATE TABLE ai_audit_hash_outbox_event (
    id UUID NOT NULL,
    audit_event_id UUID NOT NULL,
    owner_account_id UUID,
    conversation_id UUID,
    audit_hash_sha256 VARCHAR(64) NOT NULL,
    event_type VARCHAR(96) NOT NULL,
    topic VARCHAR(128) NOT NULL,
    payload_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    published_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_ai_audit_hash_outbox_event PRIMARY KEY (id),
    CONSTRAINT uk_ai_audit_hash_outbox_audit_event UNIQUE (audit_event_id),
    CONSTRAINT ck_ai_audit_hash_outbox_hash CHECK (audit_hash_sha256 ~ '^[0-9a-f]{64}$')
);

CREATE INDEX idx_ai_audit_hash_outbox_created
    ON ai_audit_hash_outbox_event (created_at, id);
