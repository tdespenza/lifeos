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

/** Versioned financial target whose progress is the exact sum of immutable contribution rows. */
@Entity
@Table(name = "financial_goal")
public class FinancialGoal {

    @Id
    private UUID id;

    @Column(name = "owner_account_id", nullable = false, updatable = false)
    private UUID ownerAccountId;

    @Column(name = "tenant_id", nullable = false, updatable = false, length = 255)
    private String tenantId;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "target_minor", nullable = false)
    private long targetMinor;

    @Column(name = "target_date")
    private LocalDate targetDate;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected FinancialGoal() {
        // required by JPA
    }

    public FinancialGoal(
            UUID id,
            UUID ownerAccountId,
            String tenantId,
            String name,
            String currency,
            long targetMinor,
            LocalDate targetDate) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.ownerAccountId = Objects.requireNonNull(ownerAccountId, "ownerAccountId must not be null");
        this.tenantId = bounded(tenantId, 255, "tenantId");
        this.name = bounded(name, 120, "name");
        this.currency = Money.requireCurrency(currency);
        this.targetMinor = Money.requirePositiveMinor(targetMinor, "targetMinor");
        this.targetDate = targetDate;
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    public void update(String name, long targetMinor, LocalDate targetDate) {
        this.name = bounded(name, 120, "name");
        this.targetMinor = Money.requirePositiveMinor(targetMinor, "targetMinor");
        this.targetDate = targetDate;
        updatedAt = Instant.now();
    }

    /** Causes JPA's optimistic version to advance when an immutable contribution is appended. */
    public void touch() {
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

    public String getName() {
        return name;
    }

    public String getCurrency() {
        return currency;
    }

    public long getTargetMinor() {
        return targetMinor;
    }

    public LocalDate getTargetDate() {
        return targetDate;
    }

    public long getVersion() {
        return version;
    }

    private static String bounded(String value, int maximumLength, String name) {
        if (value == null || value.isBlank() || value.trim().length() > maximumLength) {
            throw new IllegalArgumentException(name + " must be bounded and non-blank");
        }
        return value.trim();
    }
}
