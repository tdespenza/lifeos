CREATE TABLE analytics_metric_history (
    id UUID NOT NULL,
    owner_account_id UUID NOT NULL,
    tenant_id VARCHAR(255) NOT NULL,
    metric_key VARCHAR(80) NOT NULL,
    period_days INTEGER NOT NULL,
    observation_date DATE NOT NULL,
    metric_value BIGINT NOT NULL,
    source_version VARCHAR(80) NOT NULL,
    CONSTRAINT pk_analytics_metric_history PRIMARY KEY (id),
    CONSTRAINT uk_analytics_metric_history_scope UNIQUE (
        owner_account_id, tenant_id, metric_key, period_days, observation_date),
    CONSTRAINT ck_analytics_metric_history_value CHECK (metric_value >= 0),
    CONSTRAINT ck_analytics_metric_history_period CHECK (period_days BETWEEN 1 AND 90)
);

CREATE INDEX idx_analytics_metric_history_trend
    ON analytics_metric_history (owner_account_id, tenant_id, metric_key, period_days, observation_date);
