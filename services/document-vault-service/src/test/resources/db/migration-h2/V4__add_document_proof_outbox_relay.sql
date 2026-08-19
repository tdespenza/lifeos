ALTER TABLE document_proof_outbox_event ADD COLUMN payload_json TEXT NOT NULL DEFAULT '{}';
ALTER TABLE document_proof_outbox_event ADD COLUMN attempt_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE document_proof_outbox_event ADD COLUMN next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE document_proof_outbox_event ADD COLUMN lease_token UUID;
ALTER TABLE document_proof_outbox_event ADD COLUMN lease_until TIMESTAMP WITH TIME ZONE;
ALTER TABLE document_proof_outbox_event ADD COLUMN published_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE document_proof_outbox_event ADD COLUMN dead_lettered_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE document_proof_outbox_event ADD COLUMN last_failure_code VARCHAR(64);

ALTER TABLE document_proof_outbox_event
    ADD CONSTRAINT ck_document_proof_outbox_attempt_count CHECK (attempt_count >= 0);

UPDATE document_proof_outbox_event
SET event_type = 'com.lifeos.document.proof.requested.v1'
WHERE event_type = 'document.proof.requested.v1';

ALTER TABLE document_proof_outbox_event DROP CONSTRAINT ck_document_proof_outbox_event_type;
ALTER TABLE document_proof_outbox_event
    ADD CONSTRAINT ck_document_proof_outbox_event_type
        CHECK (event_type = 'com.lifeos.document.proof.requested.v1');

CREATE INDEX idx_document_proof_outbox_claimable
    ON document_proof_outbox_event (next_attempt_at, created_at, id);

CREATE TABLE document_proof_outbox_dead_letter (
    id UUID NOT NULL,
    outbox_event_id UUID NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    failure_code VARCHAR(64) NOT NULL,
    payload_json TEXT NOT NULL,
    attempt_count INTEGER NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_document_proof_outbox_dead_letter PRIMARY KEY (id),
    CONSTRAINT uk_document_proof_outbox_dead_letter_event UNIQUE (outbox_event_id),
    CONSTRAINT ck_document_proof_outbox_dead_letter_attempt CHECK (attempt_count >= 1)
);
