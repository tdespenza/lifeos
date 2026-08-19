package com.lifeos.finance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/** Immutable financial posting whose category can only change through a separately recorded correction. */
@Entity
@Table(name = "financial_transaction")
public class FinancialTransaction {

    @Id
    private UUID id;

    @Column(name = "owner_account_id", nullable = false, updatable = false)
    private UUID ownerAccountId;

    @Column(name = "tenant_id", nullable = false, updatable = false, length = 255)
    private String tenantId;

    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 16)
    private TransactionDirection direction;

    @Column(name = "occurred_on", nullable = false, updatable = false)
    private LocalDate occurredOn;

    @Column(updatable = false, length = 120)
    private String merchant;

    @Column(name = "initial_category", nullable = false, updatable = false, length = 64)
    private String initialCategory;

    @Column(name = "current_category", nullable = false, length = 64)
    private String currentCategory;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected FinancialTransaction() {
        // required by JPA
    }

    public FinancialTransaction(
            UUID id,
            UUID ownerAccountId,
            String tenantId,
            String currency,
            long amountMinor,
            TransactionDirection direction,
            LocalDate occurredOn,
            String merchant,
            String category) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.ownerAccountId = Objects.requireNonNull(ownerAccountId, "ownerAccountId must not be null");
        this.tenantId = bounded(tenantId, 255, "tenantId");
        this.currency = Money.requireCurrency(currency);
        this.amountMinor = Money.requirePositiveMinor(amountMinor, "amountMinor");
        this.direction = Objects.requireNonNull(direction, "direction must not be null");
        this.occurredOn = Objects.requireNonNull(occurredOn, "occurredOn must not be null");
        this.merchant = optionalBounded(merchant, 120, "merchant");
        initialCategory = bounded(category, 64, "category");
        currentCategory = initialCategory;
        createdAt = Instant.now();
    }

    public String correctCategory(String category) {
        String corrected = bounded(category, 64, "category");
        if (corrected.equals(currentCategory)) {
            throw new IllegalArgumentException("category correction must change the current category");
        }
        String previous = currentCategory;
        currentCategory = corrected;
        return previous;
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

    public String getCurrency() {
        return currency;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public TransactionDirection getDirection() {
        return direction;
    }

    public LocalDate getOccurredOn() {
        return occurredOn;
    }

    public String getMerchant() {
        return merchant;
    }

    public String getInitialCategory() {
        return initialCategory;
    }

    public String getCurrentCategory() {
        return currentCategory;
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

    private static String optionalBounded(String value, int maximumLength, String name) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return bounded(value, maximumLength, name);
    }
}
