-- A commitment over the redacted audit metadata. Nullable for pre-existing V1/V2 rows; new
-- events always populate it. Prompt, completion, bearer, and address content is never hashed.
ALTER TABLE assistant_request_audit_event
    ADD COLUMN audit_hash_sha256 VARCHAR(64);

ALTER TABLE assistant_request_audit_event
    ADD CONSTRAINT ck_assistant_request_audit_hash
        CHECK (audit_hash_sha256 IS NULL OR audit_hash_sha256 ~ '^[0-9a-f]{64}$');
