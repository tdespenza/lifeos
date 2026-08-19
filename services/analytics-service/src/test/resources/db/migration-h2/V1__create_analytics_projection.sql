CREATE TABLE analytics_metric_snapshot (
    id UUID NOT NULL,
    owner_account_id UUID NOT NULL,
    tenant_id VARCHAR(255) NOT NULL,
    metric_key VARCHAR(80) NOT NULL,
    metric_value BIGINT NOT NULL,
    period_days INTEGER NOT NULL,
    observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    source_version VARCHAR(80) NOT NULL,
    CONSTRAINT pk_analytics_metric_snapshot PRIMARY KEY (id),
    CONSTRAINT uk_analytics_metric_snapshot_scope UNIQUE (owner_account_id, tenant_id, metric_key, period_days),
    CONSTRAINT ck_analytics_metric_snapshot_value CHECK (metric_value >= 0),
    CONSTRAINT ck_analytics_metric_snapshot_period CHECK (period_days BETWEEN 1 AND 90)
);

CREATE INDEX idx_analytics_metric_snapshot_dashboard
    ON analytics_metric_snapshot (owner_account_id, tenant_id, period_days, metric_key);

CREATE TABLE analytics_event_inbox (
    event_id UUID NOT NULL,
    event_type VARCHAR(200) NOT NULL,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_analytics_event_inbox PRIMARY KEY (event_id)
);
