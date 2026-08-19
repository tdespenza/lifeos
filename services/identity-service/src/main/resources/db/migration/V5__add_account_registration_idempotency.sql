-- Durable public-registration retries. Raw retry keys and payload values, including passwords,
-- never enter this table: both digests are independently HMAC-SHA-256 protected.
CREATE TABLE IF NOT EXISTS account_registration_idempotency (
    id UUID NOT NULL,
    idempotency_key_hash VARCHAR(64) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    account_id UUID,
    state VARCHAR(16) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_account_registration_idempotency PRIMARY KEY (id),
    CONSTRAINT fk_account_registration_idempotency_account
        FOREIGN KEY (account_id) REFERENCES user_account (id) ON DELETE SET NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_account_registration_idempotency_key
    ON account_registration_idempotency (idempotency_key_hash);
CREATE INDEX IF NOT EXISTS idx_account_registration_idempotency_account
    ON account_registration_idempotency (account_id);
