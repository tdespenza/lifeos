-- Story 5.2: explicit goal lifecycle state, optimistic representation versions, and durable
-- idempotency reservations for update/complete/archive commands. Existing goals remain ACTIVE.
ALTER TABLE goal ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE goal ADD COLUMN IF NOT EXISTS status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE goal ADD COLUMN IF NOT EXISTS completed_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE goal ADD COLUMN IF NOT EXISTS archived_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE goal ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE goal ADD COLUMN IF NOT EXISTS due_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE goal ADD COLUMN IF NOT EXISTS priority INTEGER NOT NULL DEFAULT 3;

ALTER TABLE goal ADD CONSTRAINT ck_goal_lifecycle_state
    CHECK (
        (status = 'ACTIVE' AND completed_at IS NULL AND archived_at IS NULL)
        OR (status = 'COMPLETED' AND completed_at IS NOT NULL AND archived_at IS NULL)
        OR (status = 'ARCHIVED' AND archived_at IS NOT NULL)
    );

CREATE TABLE goal_mutation_idempotency (
    id UUID NOT NULL,
    actor_account_id UUID NOT NULL,
    tenant_id VARCHAR(255) NOT NULL,
    goal_id UUID NOT NULL,
    operation VARCHAR(16) NOT NULL,
    idempotency_key_hash VARCHAR(64) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    expected_version BIGINT NOT NULL,
    state VARCHAR(16) NOT NULL,
    result_title VARCHAR(255),
    result_status VARCHAR(16),
    result_version BIGINT,
    result_created_at TIMESTAMP WITH TIME ZONE,
    result_updated_at TIMESTAMP WITH TIME ZONE,
    result_completed_at TIMESTAMP WITH TIME ZONE,
    result_archived_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_goal_mutation_idempotency PRIMARY KEY (id),
    CONSTRAINT uk_goal_mutation_idempotency_scope_key
        UNIQUE (actor_account_id, tenant_id, goal_id, operation, idempotency_key_hash),
    CONSTRAINT ck_goal_mutation_idempotency_operation
        CHECK (operation IN ('UPDATE', 'COMPLETE', 'ARCHIVE')),
    CONSTRAINT ck_goal_mutation_idempotency_expected_version
        CHECK (expected_version >= 0),
    CONSTRAINT ck_goal_mutation_idempotency_result_version
        CHECK (result_version IS NULL OR result_version >= 0),
    CONSTRAINT ck_goal_mutation_idempotency_state
        CHECK (
            (state = 'PENDING'
                AND completed_at IS NULL
                AND result_title IS NULL
                AND result_status IS NULL
                AND result_version IS NULL
                AND result_created_at IS NULL
                AND result_updated_at IS NULL
                AND result_completed_at IS NULL
                AND result_archived_at IS NULL)
            OR (state = 'COMPLETED'
                AND completed_at IS NOT NULL
                AND result_title IS NOT NULL
                AND result_status IS NOT NULL
                AND result_version IS NOT NULL
                AND result_created_at IS NOT NULL
                AND result_updated_at IS NOT NULL)
        ),
    CONSTRAINT ck_goal_mutation_idempotency_snapshot_lifecycle
        CHECK (
            state = 'PENDING'
            OR (result_status = 'ACTIVE'
                AND result_completed_at IS NULL
                AND result_archived_at IS NULL)
            OR (result_status = 'COMPLETED'
                AND result_completed_at IS NOT NULL
                AND result_archived_at IS NULL)
            OR (result_status = 'ARCHIVED'
                AND result_archived_at IS NOT NULL)
        )
);

CREATE INDEX idx_goal_mutation_idempotency_goal
    ON goal_mutation_idempotency (goal_id);

ALTER TABLE goal_mutation_idempotency ADD COLUMN IF NOT EXISTS result_priority INTEGER;
ALTER TABLE goal_mutation_idempotency ADD COLUMN IF NOT EXISTS result_due_at TIMESTAMP WITH TIME ZONE;
