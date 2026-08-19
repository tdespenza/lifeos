CREATE TABLE calendar_event (
    id UUID PRIMARY KEY,
    owner_account_id UUID NOT NULL,
    tenant_id VARCHAR(255) NOT NULL,
    title VARCHAR(140) NOT NULL,
    description TEXT,
    start_at TIMESTAMP WITH TIME ZONE NOT NULL,
    end_at TIMESTAMP WITH TIME ZONE NOT NULL,
    time_zone VARCHAR(64) NOT NULL,
    recurrence_rule VARCHAR(128),
    recurrence_revision BIGINT NOT NULL,
    origin_correlation_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL,
    CONSTRAINT calendar_event_interval_valid CHECK (end_at > start_at),
    CONSTRAINT calendar_event_status_valid CHECK (status IN ('ACTIVE', 'CANCELLED'))
);

CREATE INDEX calendar_event_owner_start_idx ON calendar_event (tenant_id, owner_account_id, start_at);

CREATE TABLE calendar_event_reminder (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL REFERENCES calendar_event(id),
    minutes_before INTEGER NOT NULL,
    requested_channels VARCHAR(128) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT calendar_event_reminder_minutes_valid CHECK (minutes_before >= 0 AND minutes_before <= 10080),
    CONSTRAINT calendar_event_reminder_unique UNIQUE (event_id, minutes_before)
);

CREATE TABLE calendar_occurrence (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL REFERENCES calendar_event(id),
    owner_account_id UUID NOT NULL,
    tenant_id VARCHAR(255) NOT NULL,
    recurrence_revision BIGINT NOT NULL,
    start_at TIMESTAMP WITH TIME ZONE NOT NULL,
    end_at TIMESTAMP WITH TIME ZONE NOT NULL,
    time_zone VARCHAR(64) NOT NULL,
    origin_correlation_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT calendar_occurrence_interval_valid CHECK (end_at > start_at),
    CONSTRAINT calendar_occurrence_status_valid CHECK (status IN ('ACTIVE', 'CANCELLED')),
    CONSTRAINT calendar_occurrence_series_unique UNIQUE (event_id, recurrence_revision, start_at)
);

CREATE INDEX calendar_occurrence_owner_window_idx
    ON calendar_occurrence (tenant_id, owner_account_id, status, start_at, end_at);

CREATE TABLE calendar_time_block (
    id UUID PRIMARY KEY,
    owner_account_id UUID NOT NULL,
    tenant_id VARCHAR(255) NOT NULL,
    link_type VARCHAR(16) NOT NULL,
    linked_resource_id UUID,
    start_at TIMESTAMP WITH TIME ZONE NOT NULL,
    end_at TIMESTAMP WITH TIME ZONE NOT NULL,
    time_zone VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL,
    CONSTRAINT calendar_time_block_interval_valid CHECK (end_at > start_at),
    CONSTRAINT calendar_time_block_status_valid CHECK (status IN ('ACTIVE', 'CANCELLED')),
    CONSTRAINT calendar_time_block_link_valid CHECK (
        (link_type = 'FOCUS' AND linked_resource_id IS NULL)
        OR (link_type IN ('GOAL', 'TASK') AND linked_resource_id IS NOT NULL)
    )
);

CREATE INDEX calendar_time_block_owner_window_idx
    ON calendar_time_block (tenant_id, owner_account_id, status, start_at, end_at);

CREATE TABLE calendar_schedule_lock (
    owner_account_id UUID PRIMARY KEY,
    tenant_id VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL
);

CREATE TABLE calendar_reminder (
    id UUID PRIMARY KEY,
    occurrence_id UUID NOT NULL REFERENCES calendar_occurrence(id),
    event_id UUID NOT NULL REFERENCES calendar_event(id),
    owner_account_id UUID NOT NULL,
    tenant_id VARCHAR(255) NOT NULL,
    minutes_before INTEGER NOT NULL,
    requested_channels VARCHAR(128) NOT NULL,
    due_at TIMESTAMP WITH TIME ZONE NOT NULL,
    event_time_zone VARCHAR(64) NOT NULL,
    notification_event_id UUID NOT NULL,
    correlation_id UUID NOT NULL,
    state VARCHAR(16) NOT NULL,
    lease_token UUID,
    lease_expires_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL,
    CONSTRAINT calendar_reminder_minutes_valid CHECK (minutes_before >= 0 AND minutes_before <= 10080),
    CONSTRAINT calendar_reminder_state_valid CHECK (state IN ('SCHEDULED', 'LEASED', 'OUTBOXED', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT calendar_reminder_occurrence_offset_unique UNIQUE (occurrence_id, minutes_before),
    CONSTRAINT calendar_reminder_notification_event_unique UNIQUE (notification_event_id)
);

CREATE INDEX calendar_reminder_due_claim_idx ON calendar_reminder (state, due_at, lease_expires_at);

CREATE TABLE calendar_outbox_event (
    id UUID PRIMARY KEY,
    reminder_id UUID NOT NULL REFERENCES calendar_reminder(id),
    aggregate_id UUID NOT NULL,
    aggregate_version BIGINT NOT NULL,
    event_type VARCHAR(200) NOT NULL,
    topic VARCHAR(249) NOT NULL,
    partition_key VARCHAR(255) NOT NULL,
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
    CONSTRAINT calendar_outbox_state_valid CHECK (state IN ('PENDING', 'IN_FLIGHT', 'PUBLISHED', 'DEAD_LETTER', 'CANCELLED')),
    CONSTRAINT calendar_outbox_reminder_unique UNIQUE (reminder_id)
);

CREATE INDEX calendar_outbox_claim_idx ON calendar_outbox_event (state, available_at, lease_expires_at);

CREATE TABLE calendar_outbox_dead_letter (
    id UUID PRIMARY KEY,
    outbox_event_id UUID NOT NULL UNIQUE REFERENCES calendar_outbox_event(id),
    attempt_count INTEGER NOT NULL,
    error_code VARCHAR(80) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE calendar_mutation_idempotency (
    id UUID PRIMARY KEY,
    actor_account_id UUID NOT NULL,
    tenant_id VARCHAR(255) NOT NULL,
    operation VARCHAR(64) NOT NULL,
    resource_scope VARCHAR(128) NOT NULL,
    idempotency_key_hash VARCHAR(64) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    expected_version BIGINT,
    state VARCHAR(16) NOT NULL,
    response_status INTEGER,
    response_location VARCHAR(255),
    response_json TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE,
    version BIGINT NOT NULL,
    CONSTRAINT calendar_mutation_idempotency_state_valid CHECK (state IN ('PENDING', 'COMPLETED')),
    CONSTRAINT calendar_mutation_idempotency_scope_unique UNIQUE (
        actor_account_id, tenant_id, operation, resource_scope, idempotency_key_hash
    )
);

CREATE TABLE calendar_security_audit_event (
    id UUID PRIMARY KEY,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    actor_account_id UUID,
    session_id UUID,
    action VARCHAR(80) NOT NULL,
    outcome VARCHAR(32) NOT NULL,
    target_type VARCHAR(64) NOT NULL,
    target_id UUID,
    correlation_id VARCHAR(64) NOT NULL,
    client_fingerprint VARCHAR(64),
    reason_code VARCHAR(80)
);

CREATE INDEX calendar_security_audit_event_time_idx ON calendar_security_audit_event (occurred_at);
