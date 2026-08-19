ALTER TABLE assistant_request_audit_event
    ADD COLUMN audit_hash_sha256 VARCHAR(64);

ALTER TABLE assistant_request_audit_event
    ADD CONSTRAINT ck_assistant_request_audit_hash
        CHECK (audit_hash_sha256 IS NULL OR REGEXP_LIKE(audit_hash_sha256, '^[0-9a-f]{64}$'));
