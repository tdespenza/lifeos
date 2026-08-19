ALTER TABLE trust_document_proof_request
    ADD COLUMN anchor_idempotency_key_hash VARCHAR(64),
    ADD COLUMN transaction_hash VARCHAR(66),
    ADD COLUMN block_number BIGINT,
    ADD COLUMN last_failure_code VARCHAR(64),
    ADD COLUMN updated_at TIMESTAMPTZ;

UPDATE trust_document_proof_request SET updated_at = received_at WHERE updated_at IS NULL;

ALTER TABLE trust_document_proof_request
    ALTER COLUMN updated_at SET NOT NULL;

ALTER TABLE trust_document_proof_request DROP CONSTRAINT IF EXISTS trust_document_proof_request_state_check;
ALTER TABLE trust_document_proof_request
    ADD CONSTRAINT ck_trust_document_proof_state
    CHECK (state IN ('PENDING_EXTERNAL_ANCHOR', 'SUBMITTING', 'SUBMITTED', 'CONFIRMED', 'FAILED'));

ALTER TABLE trust_document_proof_request
    ADD CONSTRAINT ck_trust_document_proof_anchor_key
    CHECK (anchor_idempotency_key_hash IS NULL OR anchor_idempotency_key_hash ~ '^[0-9a-f]{64}$');
