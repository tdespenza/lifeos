-- Epic 3 foundation: durable inbox dedupe, notification state, independently retryable channel
-- deliveries, dead letters, endpoint registry, and an application-owned transactional outbox.
-- Event payloads may contain user-visible content; contact destinations and provider secrets do not.

CREATE TABLE notification_inbox_event (
    event_id UUID NOT NULL,
    source VARCHAR(512) NOT NULL,
    event_type VARCHAR(200) NOT NULL,
    correlation_id UUID NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    state VARCHAR(16) NOT NULL,
    notification_id UUID,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_notification_inbox_event PRIMARY KEY (event_id),
    CONSTRAINT ck_notification_inbox_event_state CHECK (
        (state = 'RECEIVED' AND processed_at IS NULL)
        OR (state = 'PROCESSED' AND processed_at IS NOT NULL AND notification_id IS NOT NULL)
    )
);

CREATE TABLE notification_subject_sequence (
    recipient_account_id UUID NOT NULL,
    last_sequence BIGINT NOT NULL,
    CONSTRAINT pk_notification_subject_sequence PRIMARY KEY (recipient_account_id),
    CONSTRAINT ck_notification_subject_sequence_nonnegative CHECK (last_sequence >= 0)
);

CREATE TABLE notification_record (
    id UUID NOT NULL,
    source_event_id UUID NOT NULL,
    correlation_id UUID NOT NULL,
    recipient_account_id UUID NOT NULL,
    tenant_id VARCHAR(255) NOT NULL,
    sequence_number BIGINT NOT NULL,
    category VARCHAR(64) NOT NULL,
    priority VARCHAR(16) NOT NULL,
    title VARCHAR(140) NOT NULL,
    body VARCHAR(4000) NOT NULL,
    action_uri VARCHAR(2048),
    expires_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_notification_record PRIMARY KEY (id),
    CONSTRAINT uk_notification_record_source_event UNIQUE (source_event_id),
    CONSTRAINT uk_notification_record_recipient_sequence UNIQUE (recipient_account_id, sequence_number),
    CONSTRAINT ck_notification_record_sequence_positive CHECK (sequence_number > 0),
    CONSTRAINT ck_notification_record_priority CHECK (priority IN ('LOW', 'NORMAL', 'HIGH'))
);

CREATE INDEX idx_notification_record_recipient_sequence
    ON notification_record (recipient_account_id, sequence_number);

CREATE TABLE notification_endpoint (
    id UUID NOT NULL,
    owner_account_id UUID NOT NULL,
    channel VARCHAR(16) NOT NULL,
    destination_ciphertext VARCHAR(8192) NOT NULL,
    destination_hash VARCHAR(64) NOT NULL,
    enabled BOOLEAN NOT NULL,
    disabled_reason VARCHAR(80),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT pk_notification_endpoint PRIMARY KEY (id),
    CONSTRAINT uk_notification_endpoint_owner_channel_destination
        UNIQUE (owner_account_id, channel, destination_hash),
    CONSTRAINT ck_notification_endpoint_channel CHECK (channel IN ('EMAIL', 'PUSH')),
    CONSTRAINT ck_notification_endpoint_disabled_reason CHECK (
        (enabled = TRUE AND disabled_reason IS NULL)
        OR (enabled = FALSE AND disabled_reason IS NOT NULL)
    )
);

CREATE INDEX idx_notification_endpoint_owner_channel_enabled
    ON notification_endpoint (owner_account_id, channel, enabled);

-- Durable retry reservation for endpoint enrollment. The original destination is only encrypted
-- in notification_endpoint; this table retains SHA-256 digests rather than raw header/body data.
CREATE TABLE notification_endpoint_registration_idempotency (
    id UUID NOT NULL,
    owner_account_id UUID NOT NULL,
    idempotency_key_hash VARCHAR(64) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    endpoint_id UUID NOT NULL,
    state VARCHAR(16) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_notification_endpoint_registration_idempotency PRIMARY KEY (id),
    CONSTRAINT uk_notification_endpoint_registration_idempotency_scope_key
        UNIQUE (owner_account_id, idempotency_key_hash),
    CONSTRAINT ck_notification_endpoint_registration_idempotency_state CHECK (
        (state = 'PENDING' AND completed_at IS NULL)
        OR (state = 'COMPLETED' AND completed_at IS NOT NULL)
    )
);

CREATE INDEX idx_notification_endpoint_registration_idempotency_endpoint
    ON notification_endpoint_registration_idempotency (endpoint_id);

-- Security-relevant notification actions. This table intentionally has no endpoint destination,
-- provider response, bearer token, session proof, or request body column.
CREATE TABLE notification_security_audit_event (
    id UUID NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    actor_account_id UUID,
    session_id UUID,
    event_type VARCHAR(64) NOT NULL,
    outcome VARCHAR(16) NOT NULL,
    target_id UUID,
    correlation_id UUID,
    reason_code VARCHAR(80) NOT NULL,
    CONSTRAINT pk_notification_security_audit_event PRIMARY KEY (id),
    CONSTRAINT ck_notification_security_audit_event_outcome CHECK (
        outcome IN ('SUCCESS', 'DENIED', 'UNAVAILABLE')
    )
);

CREATE INDEX idx_notification_security_audit_actor_time
    ON notification_security_audit_event (actor_account_id, occurred_at);

CREATE TABLE notification_delivery (
    id UUID NOT NULL,
    notification_id UUID NOT NULL,
    source_event_id UUID NOT NULL,
    recipient_account_id UUID NOT NULL,
    channel VARCHAR(16) NOT NULL,
    endpoint_id UUID,
    state VARCHAR(24) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL,
    lease_token UUID,
    lease_expires_at TIMESTAMP WITH TIME ZONE,
    last_reason_code VARCHAR(80),
    provider_message_id VARCHAR(255),
    delivered_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT pk_notification_delivery PRIMARY KEY (id),
    CONSTRAINT fk_notification_delivery_notification FOREIGN KEY (notification_id)
        REFERENCES notification_record (id),
    CONSTRAINT fk_notification_delivery_endpoint FOREIGN KEY (endpoint_id)
        REFERENCES notification_endpoint (id),
    CONSTRAINT ck_notification_delivery_channel CHECK (channel IN ('EMAIL', 'PUSH', 'REALTIME')),
    CONSTRAINT ck_notification_delivery_state CHECK (
        state IN ('PENDING', 'IN_FLIGHT', 'RETRY_SCHEDULED', 'DELIVERED', 'SKIPPED', 'DEAD_LETTERED')
    ),
    CONSTRAINT ck_notification_delivery_attempt_count CHECK (attempt_count >= 0),
    CONSTRAINT ck_notification_delivery_lease CHECK (
        (state = 'IN_FLIGHT' AND lease_token IS NOT NULL AND lease_expires_at IS NOT NULL)
        OR (state <> 'IN_FLIGHT' AND lease_token IS NULL AND lease_expires_at IS NULL)
    ),
    CONSTRAINT ck_notification_delivery_terminal_time CHECK (
        (state = 'DELIVERED' AND delivered_at IS NOT NULL)
        OR (state <> 'DELIVERED')
    )
);

CREATE INDEX idx_notification_delivery_due
    ON notification_delivery (state, next_attempt_at, created_at);
CREATE INDEX idx_notification_delivery_recipient
    ON notification_delivery (recipient_account_id, created_at);

CREATE TABLE notification_dead_letter (
    id UUID NOT NULL,
    delivery_id UUID NOT NULL,
    source_event_id UUID NOT NULL,
    recipient_account_id UUID NOT NULL,
    channel VARCHAR(16) NOT NULL,
    attempts INTEGER NOT NULL,
    reason_code VARCHAR(80) NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    replay_count INTEGER NOT NULL DEFAULT 0,
    last_replayed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_notification_dead_letter PRIMARY KEY (id),
    CONSTRAINT uk_notification_dead_letter_delivery UNIQUE (delivery_id),
    CONSTRAINT ck_notification_dead_letter_attempts CHECK (attempts >= 0),
    CONSTRAINT ck_notification_dead_letter_replay_count CHECK (replay_count >= 0)
);

CREATE INDEX idx_notification_dead_letter_source_event
    ON notification_dead_letter (source_event_id, created_at);

CREATE TABLE notification_outbox_event (
    id UUID NOT NULL,
    aggregate_id UUID NOT NULL,
    aggregate_version BIGINT NOT NULL,
    event_type VARCHAR(200) NOT NULL,
    topic VARCHAR(249) NOT NULL,
    partition_key VARCHAR(255) NOT NULL,
    payload_json TEXT NOT NULL,
    headers_json TEXT NOT NULL,
    state VARCHAR(16) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMP WITH TIME ZONE NOT NULL,
    lease_token UUID,
    lease_expires_at TIMESTAMP WITH TIME ZONE,
    last_error_code VARCHAR(80),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    published_at TIMESTAMP WITH TIME ZONE,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT pk_notification_outbox_event PRIMARY KEY (id),
    CONSTRAINT uk_notification_outbox_event_aggregate_type_version
        UNIQUE (aggregate_id, event_type, aggregate_version),
    CONSTRAINT ck_notification_outbox_event_state CHECK (state IN ('PENDING', 'IN_FLIGHT', 'PUBLISHED')),
    CONSTRAINT ck_notification_outbox_event_attempt_count CHECK (attempt_count >= 0),
    CONSTRAINT ck_notification_outbox_event_lease CHECK (
        (state = 'IN_FLIGHT' AND lease_token IS NOT NULL AND lease_expires_at IS NOT NULL)
        OR (state <> 'IN_FLIGHT' AND lease_token IS NULL AND lease_expires_at IS NULL)
    )
);

CREATE INDEX idx_notification_outbox_due
    ON notification_outbox_event (state, available_at, created_at);
