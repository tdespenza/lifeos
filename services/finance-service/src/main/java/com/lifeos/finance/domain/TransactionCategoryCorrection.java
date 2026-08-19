package com.lifeos.finance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable audit history for a category correction; amounts and original posting facts remain untouched. */
@Entity
@Table(name = "transaction_category_correction")
public class TransactionCategoryCorrection {

    @Id
    private UUID id;

    @Column(name = "transaction_id", nullable = false, updatable = false)
    private UUID transactionId;

    @Column(name = "owner_account_id", nullable = false, updatable = false)
    private UUID ownerAccountId;

    @Column(name = "previous_category", nullable = false, updatable = false, length = 64)
    private String previousCategory;

    @Column(name = "corrected_category", nullable = false, updatable = false, length = 64)
    private String correctedCategory;

    @Column(name = "corrected_by_account_id", nullable = false, updatable = false)
    private UUID correctedByAccountId;

    @Column(name = "corrected_at", nullable = false, updatable = false)
    private Instant correctedAt;

    protected TransactionCategoryCorrection() {
        // required by JPA
    }

    public TransactionCategoryCorrection(
            UUID transactionId,
            UUID ownerAccountId,
            String previousCategory,
            String correctedCategory,
            UUID correctedByAccountId) {
        id = UUID.randomUUID();
        this.transactionId = Objects.requireNonNull(transactionId, "transactionId must not be null");
        this.ownerAccountId = Objects.requireNonNull(ownerAccountId, "ownerAccountId must not be null");
        this.previousCategory = category(previousCategory, "previousCategory");
        this.correctedCategory = category(correctedCategory, "correctedCategory");
        if (this.previousCategory.equals(this.correctedCategory)) {
            throw new IllegalArgumentException("correctedCategory must differ from previousCategory");
        }
        this.correctedByAccountId = Objects.requireNonNull(correctedByAccountId, "correctedByAccountId must not be null");
        correctedAt = Instant.now();
    }

    public String getPreviousCategory() {
        return previousCategory;
    }

    public String getCorrectedCategory() {
        return correctedCategory;
    }

    public Instant getCorrectedAt() {
        return correctedAt;
    }

    private static String category(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 64) {
            throw new IllegalArgumentException(name + " must be bounded and non-blank");
        }
        return value;
    }
}
