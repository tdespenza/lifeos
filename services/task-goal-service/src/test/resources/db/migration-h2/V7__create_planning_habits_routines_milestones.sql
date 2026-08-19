CREATE TABLE habit (
    id UUID NOT NULL,
    name VARCHAR(120) NOT NULL,
    cadence VARCHAR(16) NOT NULL,
    time_zone VARCHAR(64) NOT NULL,
    owner_account_id UUID NOT NULL,
    tenant_id VARCHAR(255) NOT NULL,
    active BOOLEAN NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT pk_habit PRIMARY KEY (id),
    CONSTRAINT ck_habit_cadence CHECK (cadence IN ('DAILY', 'WEEKLY')),
    CONSTRAINT ck_habit_version CHECK (version >= 0)
);
CREATE INDEX idx_habit_owner_tenant ON habit (owner_account_id, tenant_id, created_at, id);
CREATE TABLE habit_occurrence (
    id UUID NOT NULL,
    habit_id UUID NOT NULL,
    owner_account_id UUID NOT NULL,
    tenant_id VARCHAR(255) NOT NULL,
    occurrence_date DATE NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_habit_occurrence PRIMARY KEY (id),
    CONSTRAINT uk_habit_occurrence_date UNIQUE (habit_id, owner_account_id, tenant_id, occurrence_date),
    CONSTRAINT fk_habit_occurrence_habit FOREIGN KEY (habit_id) REFERENCES habit (id) ON DELETE RESTRICT
);
CREATE INDEX idx_habit_occurrence_habit_date ON habit_occurrence (habit_id, occurrence_date);
CREATE TABLE routine (
    id UUID NOT NULL,
    name VARCHAR(120) NOT NULL,
    time_zone VARCHAR(64) NOT NULL,
    cadence VARCHAR(16) NOT NULL,
    activities_json TEXT NOT NULL,
    owner_account_id UUID NOT NULL,
    tenant_id VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT pk_routine PRIMARY KEY (id),
    CONSTRAINT ck_routine_cadence CHECK (cadence IN ('DAILY', 'WEEKLY', 'MONTHLY')),
    CONSTRAINT ck_routine_version CHECK (version >= 0)
);
CREATE INDEX idx_routine_owner_tenant ON routine (owner_account_id, tenant_id, created_at, id);
CREATE TABLE routine_occurrence (
    id UUID NOT NULL,
    routine_id UUID NOT NULL,
    owner_account_id UUID NOT NULL,
    tenant_id VARCHAR(255) NOT NULL,
    occurrence_date DATE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_routine_occurrence PRIMARY KEY (id),
    CONSTRAINT uk_routine_occurrence_date UNIQUE (routine_id, owner_account_id, tenant_id, occurrence_date),
    CONSTRAINT fk_routine_occurrence_routine FOREIGN KEY (routine_id) REFERENCES routine (id) ON DELETE RESTRICT
);
CREATE INDEX idx_routine_occurrence_scope ON routine_occurrence (routine_id, occurrence_date);
CREATE TABLE goal_milestone (
    id UUID NOT NULL,
    goal_id UUID NOT NULL,
    title VARCHAR(160) NOT NULL,
    criteria VARCHAR(2000),
    position INTEGER NOT NULL,
    completed BOOLEAN NOT NULL,
    owner_account_id UUID NOT NULL,
    tenant_id VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT pk_goal_milestone PRIMARY KEY (id),
    CONSTRAINT ck_goal_milestone_position CHECK (position >= 0 AND position <= 10000),
    CONSTRAINT ck_goal_milestone_version CHECK (version >= 0),
    CONSTRAINT fk_goal_milestone_goal FOREIGN KEY (goal_id) REFERENCES goal (id) ON DELETE RESTRICT
);
CREATE INDEX idx_goal_milestone_owner_goal ON goal_milestone (owner_account_id, tenant_id, goal_id, position, id);
CREATE TABLE planning_command_idempotency (
    id UUID NOT NULL,
    owner_account_id UUID NOT NULL,
    tenant_id VARCHAR(255) NOT NULL,
    operation VARCHAR(48) NOT NULL,
    key_hash VARCHAR(64) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    resource_id UUID NOT NULL,
    state VARCHAR(16) NOT NULL,
    response_snapshot TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_planning_command_idempotency PRIMARY KEY (id),
    CONSTRAINT uk_planning_command_scope_key UNIQUE (owner_account_id, tenant_id, operation, key_hash),
    CONSTRAINT ck_planning_command_state CHECK (
        (state = 'PENDING' AND response_snapshot IS NULL AND completed_at IS NULL)
        OR (state = 'COMPLETED' AND response_snapshot IS NOT NULL AND completed_at IS NOT NULL)
    )
);
