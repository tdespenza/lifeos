package com.lifeos.analytics.projection;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
import java.util.UUID;

/** One bounded daily observation used for owner-scoped trend reads. */
@Entity
@Table(
        name = "analytics_metric_history",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_analytics_metric_history_scope",
                columnNames = {"owner_account_id", "tenant_id", "metric_key", "period_days", "observation_date"}))
public class AnalyticsMetricHistory {

    @Id
    private UUID id;

    @Column(name = "owner_account_id", nullable = false)
    private UUID ownerAccountId;

    @Column(name = "tenant_id", nullable = false, length = 255)
    private String tenantId;

    @Column(name = "metric_key", nullable = false, length = 80)
    private String metricKey;

    @Column(name = "period_days", nullable = false)
    private int periodDays;

    @Column(name = "observation_date", nullable = false)
    private LocalDate observationDate;

    @Column(name = "metric_value", nullable = false)
    private long metricValue;

    @Column(name = "source_version", nullable = false, length = 80)
    private String sourceVersion;

    protected AnalyticsMetricHistory() {}

    public AnalyticsMetricHistory(
            UUID ownerAccountId,
            String tenantId,
            String metricKey,
            int periodDays,
            LocalDate observationDate,
            long metricValue,
            String sourceVersion) {
        this.id = UUID.randomUUID();
        this.ownerAccountId = ownerAccountId;
        this.tenantId = tenantId;
        this.metricKey = metricKey;
        this.periodDays = periodDays;
        this.observationDate = observationDate;
        this.metricValue = metricValue;
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

    public int getPeriodDays() {
        return periodDays;
    }

    public LocalDate getObservationDate() {
        return observationDate;
    }

    public long getMetricValue() {
        return metricValue;
    }

    public String getSourceVersion() {
        return sourceVersion;
    }

    public void replaceValue(long value, String sourceVersion) {
        this.metricValue = value;
        this.sourceVersion = sourceVersion;
    }
}
