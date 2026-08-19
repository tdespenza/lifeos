package com.lifeos.finance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/** Versioned budget allocation. A PostgreSQL exclusion constraint prevents overlapping scopes. */
@Entity
@Table(name = "finance_budget")
public class FinanceBudget {

    @Id
    private UUID id;

    @Column(name = "owner_account_id", nullable = false, updatable = false)
    private UUID ownerAccountId;

    @Column(name = "tenant_id", nullable = false, updatable = false, length = 255)
    private String tenantId;

    @Column(nullable = false, updatable = false, length = 64)
    private String category;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "allocation_minor", nullable = false)
    private long allocationMinor;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected FinanceBudget() {
        // required by JPA
    }

    public FinanceBudget(
            UUID id,
            UUID ownerAccountId,
            String tenantId,
            String category,
            String currency,
            long allocationMinor,
            LocalDate periodStart,
            LocalDate periodEnd) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.ownerAccountId = Objects.requireNonNull(ownerAccountId, "ownerAccountId must not be null");
        this.tenantId = bounded(tenantId, 255, "tenantId");
        this.category = bounded(category, 64, "category");
        this.currency = Money.requireCurrency(currency);
        this.allocationMinor = Money.requirePositiveMinor(allocationMinor, "allocationMinor");
        applyPeriod(periodStart, periodEnd);
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    public void update(String currency, long allocationMinor, LocalDate periodStart, LocalDate periodEnd) {
        this.currency = Money.requireCurrency(currency);
        this.allocationMinor = Money.requirePositiveMinor(allocationMinor, "allocationMinor");
        applyPeriod(periodStart, periodEnd);
        updatedAt = Instant.now();
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

    public String getCategory() {
        return category;
    }

    public String getCurrency() {
        return currency;
    }

    public long getAllocationMinor() {
        return allocationMinor;
    }

    public LocalDate getPeriodStart() {
        return periodStart;
    }

    public LocalDate getPeriodEnd() {
        return periodEnd;
    }

    public long getVersion() {
        return version;
    }

    private void applyPeriod(LocalDate start, LocalDate end) {
        periodStart = Objects.requireNonNull(start, "periodStart must not be null");
        periodEnd = Objects.requireNonNull(end, "periodEnd must not be null");
        if (periodEnd.isBefore(periodStart)) {
            throw new IllegalArgumentException("periodEnd must not be before periodStart");
        }
    }

    private static String bounded(String value, int maximumLength, String name) {
        if (value == null || value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(name + " must be bounded and non-blank");
        }
        return value.trim();
    }
}
