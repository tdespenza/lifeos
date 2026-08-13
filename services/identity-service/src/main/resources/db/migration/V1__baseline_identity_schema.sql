-- Baseline for all identity-service entities that predate explicit migrations.
-- `IF NOT EXISTS` makes the first controlled Flyway deployment compatible with a database
-- previously created by Hibernate. Hibernate validates the resulting shape at startup.

CREATE TABLE IF NOT EXISTS user_account (
    id UUID NOT NULL,
    email VARCHAR(255) NOT NULL,
    display_name VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    status VARCHAR(16) NOT NULL,
    CONSTRAINT pk_user_account PRIMARY KEY (id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_user_account_email ON user_account (email);

CREATE TABLE IF NOT EXISTS password_credential (
    id UUID NOT NULL,
    account_id UUID NOT NULL,
    encoded_password VARCHAR(512) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_password_credential PRIMARY KEY (id),
    CONSTRAINT fk_password_credential_account FOREIGN KEY (account_id) REFERENCES user_account (id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_password_credential_account ON password_credential (account_id);

CREATE TABLE IF NOT EXISTS auth_session (
    id UUID NOT NULL,
    account_id UUID NOT NULL,
    authentication_method VARCHAR(16),
    access_token_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked BOOLEAN NOT NULL,
    CONSTRAINT pk_auth_session PRIMARY KEY (id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_auth_session_token_hash ON auth_session (access_token_hash);

CREATE TABLE IF NOT EXISTS refresh_token_family (
    id UUID NOT NULL,
    account_id UUID NOT NULL,
    session_id UUID NOT NULL,
    active_token_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_used_at TIMESTAMP WITH TIME ZONE NOT NULL,
    refresh_expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    family_expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    idle_expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    status VARCHAR(16) NOT NULL,
    CONSTRAINT pk_refresh_token_family PRIMARY KEY (id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_refresh_family_active_hash ON refresh_token_family (active_token_hash);
CREATE INDEX IF NOT EXISTS ix_refresh_family_account ON refresh_token_family (account_id);

CREATE TABLE IF NOT EXISTS consumed_refresh_token (
    id UUID NOT NULL,
    family_id UUID NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    consumed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_consumed_refresh_token PRIMARY KEY (id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_consumed_refresh_token_hash ON consumed_refresh_token (token_hash);
CREATE INDEX IF NOT EXISTS ix_consumed_refresh_family ON consumed_refresh_token (family_id);

CREATE TABLE IF NOT EXISTS refresh_replay_record (
    id UUID NOT NULL,
    family_id UUID NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    predecessor_token_hash VARCHAR(64) NOT NULL,
    -- Preserve the legacy PostgreSQL `@Lob String` representation and existing large objects.
    encrypted_response OID,
    state VARCHAR(16) NOT NULL,
    retry_count INTEGER NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_refresh_replay_record PRIMARY KEY (id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_refresh_replay_family_key
    ON refresh_replay_record (family_id, idempotency_key);
CREATE INDEX IF NOT EXISTS ix_refresh_replay_expires ON refresh_replay_record (expires_at);

CREATE TABLE IF NOT EXISTS external_identity (
    id UUID NOT NULL,
    provider VARCHAR(64) NOT NULL,
    subject VARCHAR(255) NOT NULL,
    account_id UUID NOT NULL,
    linked_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_external_identity PRIMARY KEY (id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_external_identity_provider_subject
    ON external_identity (provider, subject);

CREATE TABLE IF NOT EXISTS security_audit_event (
    id UUID NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    account_id UUID,
    correlation_id VARCHAR(128) NOT NULL,
    client_fingerprint VARCHAR(64) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_security_audit_event PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS webauthn_credential (
    id UUID NOT NULL,
    account_id UUID NOT NULL,
    credential_id VARCHAR(1024) NOT NULL,
    user_handle VARCHAR(512) NOT NULL,
    public_key_cose VARCHAR(8192) NOT NULL,
    signature_count BIGINT NOT NULL,
    enabled BOOLEAN NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_used_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_webauthn_credential PRIMARY KEY (id),
    CONSTRAINT fk_webauthn_credential_account FOREIGN KEY (account_id) REFERENCES user_account (id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_webauthn_credential_id ON webauthn_credential (credential_id);
CREATE INDEX IF NOT EXISTS idx_webauthn_user_handle ON webauthn_credential (user_handle);
