ALTER TABLE document_proof_request DROP CONSTRAINT ck_document_proof_request_state;

ALTER TABLE document_proof_request
    ADD CONSTRAINT ck_document_proof_request_state CHECK (state IN ('REQUESTED', 'FAILED'));
