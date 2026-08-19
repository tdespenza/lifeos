package com.lifeos.analytics.projection;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

/** Durable, bounded read-model value. The row is an observation, not a mutable event ledger. */
@Entity
@Table(
        name = "analytics_metric_snapshot",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_analytics_metric_snapshot_scope",
                columnNames = {"owner_account_id", "tenant_id", "metric_key", "period_days"}))
public class AnalyticsMetricSnapshot {

    @Id
    private UUID id;

    @Column(name = "owner_account_id", nullable = false)
    private UUID ownerAccountId;

    @Column(name = "tenant_id", nullable = false, length = 255)
    private String tenantId;

    @Column(name = "metric_key", nullable = false, length = 80)
    private String metricKey;

    @Column(name = "metric_value", nullable = false)
    private long metricValue;

    @Column(name = "period_days", nullable = false)
    private int periodDays;

    @Column(name = "observed_at", nullable = false)
    private Instant observedAt;

    @Column(name = "source_version", nullable = false, length = 80)
    private String sourceVersion;

    protected AnalyticsMetricSnapshot() {}

    public AnalyticsMetricSnapshot(
            UUID ownerAccountId,
            String tenantId,
            String metricKey,
            long metricValue,
            int periodDays,
            Instant observedAt,
            String sourceVersion) {
        this.id = UUID.randomUUID();
        this.ownerAccountId = ownerAccountId;
        this.tenantId = tenantId;
        this.metricKey = metricKey;
        this.metricValue = metricValue;
        this.periodDays = periodDays;
        this.observedAt = observedAt;
        this.sourceVersion = sourceVersion;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOwnerAccountId() {
        return ownerAccountId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getMetricKey() {
        return metricKey;
    }

    public long getMetricValue() {
        return metricValue;
    }

    public int getPeriodDays() {
        return periodDays;
    }

    public Instant getObservedAt() {
        return observedAt;
    }

    public String getSourceVersion() {
        return sourceVersion;
    }

    public void replaceValue(long value, Instant observedAt, String sourceVersion) {
        this.metricValue = value;
        this.observedAt = observedAt;
        this.sourceVersion = sourceVersion;
    }
}
