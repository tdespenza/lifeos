ALTER TABLE trust_document_proof_request
    ADD anchor_idempotency_key_hash VARCHAR(64);
ALTER TABLE trust_document_proof_request ADD transaction_hash VARCHAR(66);
ALTER TABLE trust_document_proof_request ADD block_number BIGINT;
ALTER TABLE trust_document_proof_request ADD last_failure_code VARCHAR(64);
ALTER TABLE trust_document_proof_request ADD updated_at TIMESTAMP WITH TIME ZONE;

UPDATE trust_document_proof_request SET updated_at = received_at WHERE updated_at IS NULL;

ALTER TABLE trust_document_proof_request ALTER COLUMN updated_at SET NOT NULL;

ALTER TABLE trust_document_proof_request DROP CONSTRAINT IF EXISTS ck_trust_document_proof_state;
ALTER TABLE trust_document_proof_request
    ADD CONSTRAINT ck_trust_document_proof_state
    CHECK (state IN ('PENDING_EXTERNAL_ANCHOR', 'SUBMITTING', 'SUBMITTED', 'CONFIRMED', 'FAILED'));
