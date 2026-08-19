CREATE TABLE IF NOT EXISTS passkey_recovery_code (
    id UUID NOT NULL,
    account_id UUID NOT NULL,
    code_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    used_at TIMESTAMP WITH TIME ZONE,
    version BIGINT NOT NULL,
    CONSTRAINT pk_passkey_recovery_code PRIMARY KEY (id),
    CONSTRAINT fk_passkey_recovery_code_account FOREIGN KEY (account_id) REFERENCES user_account (id),
    CONSTRAINT uk_passkey_recovery_code_hash UNIQUE (code_hash),
    CONSTRAINT ck_passkey_recovery_code_window CHECK (expires_at > created_at)
);

CREATE INDEX IF NOT EXISTS idx_passkey_recovery_account
    ON passkey_recovery_code (account_id, expires_at);
