-- Epic 5: owner-scoped Tasks, durable exact command replay, and a persisted Task/Goal DAG.
-- Polymorphic dependency endpoints are intentionally validated by the service; database foreign
-- keys cannot safely enforce references that span the task and goal tables.
CREATE TABLE task (
    id UUID NOT NULL,
    title VARCHAR(255) NOT NULL,
    owner_account_id UUID NOT NULL,
    tenant_id VARCHAR(255) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE,
    canceled_at TIMESTAMP WITH TIME ZONE,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT pk_task PRIMARY KEY (id),
    CONSTRAINT ck_task_lifecycle_state CHECK (
        (status = 'ACTIVE' AND completed_at IS NULL AND canceled_at IS NULL)
        OR (status = 'COMPLETED' AND completed_at IS NOT NULL AND canceled_at IS NULL)
        OR (status = 'CANCELED' AND completed_at IS NULL AND canceled_at IS NOT NULL)
    ),
    CONSTRAINT ck_task_version CHECK (version >= 0)
);

CREATE INDEX idx_task_owner_tenant ON task (owner_account_id, tenant_id, created_at, id);

CREATE TABLE task_command_idempotency (
    id UUID NOT NULL,
    actor_account_id UUID NOT NULL,
    tenant_id VARCHAR(255) NOT NULL,
    operation VARCHAR(16) NOT NULL,
    target_scope VARCHAR(40) NOT NULL,
    task_id UUID NOT NULL,
    idempotency_key_hash VARCHAR(64) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    expected_version BIGINT,
    state VARCHAR(16) NOT NULL,
    result_title VARCHAR(255),
    result_status VARCHAR(16),
    result_version BIGINT,
    result_created_at TIMESTAMP WITH TIME ZONE,
    result_updated_at TIMESTAMP WITH TIME ZONE,
    result_completed_at TIMESTAMP WITH TIME ZONE,
    result_canceled_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_task_command_idempotency PRIMARY KEY (id),
    CONSTRAINT uk_task_command_idempotency_scope_key
        UNIQUE (actor_account_id, tenant_id, operation, target_scope, idempotency_key_hash),
    CONSTRAINT ck_task_command_operation
        CHECK (operation IN ('CREATE', 'UPDATE', 'COMPLETE', 'CANCEL')),
    CONSTRAINT ck_task_command_expected_version
        CHECK (
            (operation = 'CREATE' AND expected_version IS NULL)
            OR (operation <> 'CREATE' AND expected_version >= 0)
        ),
    CONSTRAINT ck_task_command_idempotency_state
        CHECK (
            (state = 'PENDING'
                AND completed_at IS NULL
                AND result_title IS NULL
                AND result_status IS NULL
                AND result_version IS NULL
                AND result_created_at IS NULL
                AND result_updated_at IS NULL
                AND result_completed_at IS NULL
                AND result_canceled_at IS NULL)
            OR (state = 'COMPLETED'
                AND completed_at IS NOT NULL
                AND result_title IS NOT NULL
                AND result_status IS NOT NULL
                AND result_version IS NOT NULL
                AND result_created_at IS NOT NULL
                AND result_updated_at IS NOT NULL)
        ),
    CONSTRAINT ck_task_command_snapshot_lifecycle
        CHECK (
            state = 'PENDING'
            OR (result_status = 'ACTIVE'
                AND result_completed_at IS NULL
                AND result_canceled_at IS NULL)
            OR (result_status = 'COMPLETED'
                AND result_completed_at IS NOT NULL
                AND result_canceled_at IS NULL)
            OR (result_status = 'CANCELED'
                AND result_completed_at IS NULL
                AND result_canceled_at IS NOT NULL)
        )
);

CREATE INDEX idx_task_command_idempotency_task ON task_command_idempotency (task_id);

-- A one-row-per-owner guard serializes graph mutations without holding locks while callers are
-- authenticated. It makes concurrent opposite-edge submissions deterministic across instances.
CREATE TABLE task_goal_dependency_guard (
    id UUID NOT NULL,
    owner_account_id UUID NOT NULL,
    tenant_id VARCHAR(255) NOT NULL,
    CONSTRAINT pk_task_goal_dependency_guard PRIMARY KEY (id),
    CONSTRAINT uk_task_goal_dependency_guard_scope UNIQUE (owner_account_id, tenant_id)
);

CREATE TABLE task_goal_dependency (
    id UUID NOT NULL,
    owner_account_id UUID NOT NULL,
    tenant_id VARCHAR(255) NOT NULL,
    predecessor_type VARCHAR(16) NOT NULL,
    predecessor_id UUID NOT NULL,
    dependent_type VARCHAR(16) NOT NULL,
    dependent_id UUID NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_task_goal_dependency PRIMARY KEY (id),
    CONSTRAINT uk_task_goal_dependency_edge
        UNIQUE (
            owner_account_id,
            tenant_id,
            predecessor_type,
            predecessor_id,
            dependent_type,
            dependent_id
        ),
    CONSTRAINT ck_task_goal_dependency_node_types
        CHECK (predecessor_type IN ('TASK', 'GOAL') AND dependent_type IN ('TASK', 'GOAL')),
    CONSTRAINT ck_task_goal_dependency_not_self
        CHECK (predecessor_type <> dependent_type OR predecessor_id <> dependent_id)
);

CREATE INDEX idx_task_goal_dependency_scope_predecessor
    ON task_goal_dependency (owner_account_id, tenant_id, predecessor_type, predecessor_id);
CREATE INDEX idx_task_goal_dependency_scope_dependent
    ON task_goal_dependency (owner_account_id, tenant_id, dependent_type, dependent_id);
