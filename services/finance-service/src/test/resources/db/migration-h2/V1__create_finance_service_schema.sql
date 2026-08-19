-- H2 equivalent of the production-owned Finance schema. PostgreSQL's btree_gist exclusion
-- constraint is intentionally tested in the Testcontainers suite; this path keeps H2 portable.
CREATE TABLE finance_budget (
    id UUID NOT NULL,
    owner_account_id UUID NOT NULL,
    tenant_id VARCHAR(255) NOT NULL,
    category VARCHAR(64) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    allocation_minor BIGINT NOT NULL,
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_finance_budget PRIMARY KEY (id),
    CONSTRAINT ck_finance_budget_allocation CHECK (allocation_minor > 0),
    CONSTRAINT ck_finance_budget_period CHECK (period_end >= period_start),
    CONSTRAINT ck_finance_budget_version CHECK (version >= 0)
);
CREATE INDEX idx_finance_budget_owner_tenant_period
    ON finance_budget (owner_account_id, tenant_id, period_start, period_end);

CREATE TABLE financial_transaction (
    id UUID NOT NULL,
    owner_account_id UUID NOT NULL,
    tenant_id VARCHAR(255) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    amount_minor BIGINT NOT NULL,
    direction VARCHAR(16) NOT NULL,
    occurred_on DATE NOT NULL,
    merchant VARCHAR(120),
    initial_category VARCHAR(64) NOT NULL,
    current_category VARCHAR(64) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_financial_transaction PRIMARY KEY (id),
    CONSTRAINT ck_financial_transaction_amount CHECK (amount_minor > 0),
    CONSTRAINT ck_financial_transaction_direction CHECK (direction IN ('INCOME', 'EXPENSE')),
    CONSTRAINT ck_financial_transaction_version CHECK (version >= 0)
);
CREATE INDEX idx_financial_transaction_owner_tenant_currency_date
    ON financial_transaction (owner_account_id, tenant_id, currency, occurred_on DESC, id DESC);
CREATE INDEX idx_financial_transaction_owner_tenant_date
    ON financial_transaction (owner_account_id, tenant_id, occurred_on DESC, id DESC);

CREATE TABLE transaction_category_correction (
    id UUID NOT NULL,
    transaction_id UUID NOT NULL,
    owner_account_id UUID NOT NULL,
    previous_category VARCHAR(64) NOT NULL,
    corrected_category VARCHAR(64) NOT NULL,
    corrected_by_account_id UUID NOT NULL,
    corrected_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_transaction_category_correction PRIMARY KEY (id),
    CONSTRAINT fk_transaction_category_correction_transaction FOREIGN KEY (transaction_id)
        REFERENCES financial_transaction (id) ON DELETE RESTRICT,
    CONSTRAINT ck_transaction_category_correction_changed CHECK (previous_category <> corrected_category)
);
CREATE INDEX idx_transaction_category_correction_transaction_time
    ON transaction_category_correction (transaction_id, corrected_at);

CREATE TABLE financial_goal (
    id UUID NOT NULL,
    owner_account_id UUID NOT NULL,
    tenant_id VARCHAR(255) NOT NULL,
    name VARCHAR(120) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    target_minor BIGINT NOT NULL,
    target_date DATE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_financial_goal PRIMARY KEY (id),
    CONSTRAINT ck_financial_goal_target CHECK (target_minor > 0),
    CONSTRAINT ck_financial_goal_version CHECK (version >= 0)
);
CREATE INDEX idx_financial_goal_owner_tenant ON financial_goal (owner_account_id, tenant_id, id);

CREATE TABLE financial_goal_contribution (
    id UUID NOT NULL,
    goal_id UUID NOT NULL,
    owner_account_id UUID NOT NULL,
    tenant_id VARCHAR(255) NOT NULL,
    amount_minor BIGINT NOT NULL,
    source_transaction_id UUID,
    contributed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_financial_goal_contribution PRIMARY KEY (id),
    CONSTRAINT fk_financial_goal_contribution_goal FOREIGN KEY (goal_id)
        REFERENCES financial_goal (id) ON DELETE RESTRICT,
    CONSTRAINT fk_financial_goal_contribution_transaction FOREIGN KEY (source_transaction_id)
        REFERENCES financial_transaction (id) ON DELETE RESTRICT,
    CONSTRAINT ck_financial_goal_contribution_amount CHECK (amount_minor > 0),
    CONSTRAINT uk_financial_goal_contribution_source UNIQUE (goal_id, source_transaction_id)
);
CREATE INDEX idx_financial_goal_contribution_goal ON financial_goal_contribution (goal_id, contributed_at);

CREATE TABLE finance_mutation_idempotency (
    id UUID NOT NULL,
    actor_account_id UUID NOT NULL,
    tenant_id VARCHAR(255) NOT NULL,
    operation VARCHAR(64) NOT NULL,
    resource_id UUID NOT NULL,
    idempotency_key_hash VARCHAR(64) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    expected_version BIGINT NOT NULL,
    state VARCHAR(16) NOT NULL,
    response_snapshot TEXT,
    response_status INTEGER,
    response_location VARCHAR(256),
    completed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_finance_mutation_idempotency PRIMARY KEY (id),
    CONSTRAINT uk_finance_mutation_idempotency_scope_key UNIQUE (
        actor_account_id, tenant_id, operation, idempotency_key_hash
    ),
    CONSTRAINT ck_finance_mutation_idempotency_expected_version CHECK (expected_version >= -1),
    CONSTRAINT ck_finance_mutation_idempotency_state CHECK (
        (state = 'PENDING'
            AND completed_at IS NULL
            AND response_snapshot IS NULL
            AND response_status IS NULL
            AND response_location IS NULL)
        OR (state = 'COMPLETED'
            AND completed_at IS NOT NULL
            AND response_snapshot IS NOT NULL
            AND response_status IN (200, 201)
            AND ((response_status = 200 AND response_location IS NULL)
                OR (response_status = 201 AND response_location IS NOT NULL)))
    )
);
CREATE INDEX idx_finance_mutation_idempotency_resource ON finance_mutation_idempotency (resource_id);

CREATE TABLE finance_security_audit_event (
    id UUID NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    account_id UUID,
    correlation_id VARCHAR(128) NOT NULL,
    client_fingerprint VARCHAR(64) NOT NULL,
    outcome_code VARCHAR(64) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_finance_security_audit_event PRIMARY KEY (id)
);
CREATE INDEX idx_finance_security_audit_event_account_time
    ON finance_security_audit_event (account_id, occurred_at);
