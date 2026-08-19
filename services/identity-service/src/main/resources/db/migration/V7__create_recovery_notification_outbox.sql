CREATE TABLE IF NOT EXISTS identity_notification_outbox_event (
    id UUID NOT NULL,
    topic VARCHAR(249) NOT NULL,
    partition_key VARCHAR(255) NOT NULL,
    event_type VARCHAR(200) NOT NULL,
    payload_json TEXT NOT NULL,
    headers_json TEXT NOT NULL,
    state VARCHAR(16) NOT NULL,
    attempt_count INTEGER NOT NULL,
    available_at TIMESTAMP WITH TIME ZONE NOT NULL,
    lease_token UUID,
    lease_expires_at TIMESTAMP WITH TIME ZONE,
    last_error_code VARCHAR(80),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    published_at TIMESTAMP WITH TIME ZONE,
    version BIGINT NOT NULL,
    CONSTRAINT pk_identity_notification_outbox_event PRIMARY KEY (id),
    CONSTRAINT ck_identity_notification_outbox_state CHECK (state IN ('PENDING', 'IN_FLIGHT', 'PUBLISHED', 'DEAD_LETTER', 'CANCELLED'))
);

CREATE INDEX IF NOT EXISTS idx_identity_notification_outbox_claim
    ON identity_notification_outbox_event (state, available_at, lease_expires_at);

CREATE TABLE IF NOT EXISTS identity_notification_outbox_dead_letter (
    id UUID NOT NULL,
    outbox_event_id UUID NOT NULL,
    attempt_count INTEGER NOT NULL,
    error_code VARCHAR(80) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_identity_notification_outbox_dead_letter PRIMARY KEY (id),
    CONSTRAINT uk_identity_notification_outbox_dead_letter_event UNIQUE (outbox_event_id),
    CONSTRAINT fk_identity_notification_outbox_dead_letter_event FOREIGN KEY (outbox_event_id)
        REFERENCES identity_notification_outbox_event (id)
);
