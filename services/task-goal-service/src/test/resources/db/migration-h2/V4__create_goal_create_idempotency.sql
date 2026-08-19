-- H2-compatible equivalent of the PostgreSQL goal-create idempotency reservation migration.
CREATE TABLE goal_create_idempotency (
    id UUID NOT NULL,
    owner_account_id UUID NOT NULL,
    tenant_id VARCHAR(255) NOT NULL,
    idempotency_key_hash VARCHAR(64) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    goal_id UUID NOT NULL,
    state VARCHAR(16) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_goal_create_idempotency PRIMARY KEY (id),
    CONSTRAINT uk_goal_create_idempotency_scope_key
        UNIQUE (owner_account_id, tenant_id, idempotency_key_hash)
);

CREATE INDEX idx_goal_create_idempotency_goal
    ON goal_create_idempotency (goal_id);
