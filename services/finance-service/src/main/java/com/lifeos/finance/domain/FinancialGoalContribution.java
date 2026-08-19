package com.lifeos.finance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable contribution to a financial target, optionally traceable to a same-currency posting. */
@Entity
@Table(name = "financial_goal_contribution")
public class FinancialGoalContribution {

    @Id
    private UUID id;

    @Column(name = "goal_id", nullable = false, updatable = false)
    private UUID goalId;

    @Column(name = "owner_account_id", nullable = false, updatable = false)
    private UUID ownerAccountId;

    @Column(name = "tenant_id", nullable = false, updatable = false, length = 255)
    private String tenantId;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @Column(name = "source_transaction_id", updatable = false)
    private UUID sourceTransactionId;

    @Column(name = "contributed_at", nullable = false, updatable = false)
    private Instant contributedAt;

    protected FinancialGoalContribution() {
        // required by JPA
    }

    public FinancialGoalContribution(
            UUID id,
            UUID goalId,
            UUID ownerAccountId,
            String tenantId,
            long amountMinor,
            UUID sourceTransactionId) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.goalId = Objects.requireNonNull(goalId, "goalId must not be null");
        this.ownerAccountId = Objects.requireNonNull(ownerAccountId, "ownerAccountId must not be null");
        if (tenantId == null || tenantId.isBlank() || tenantId.length() > 255) {
            throw new IllegalArgumentException("tenantId must be bounded and non-blank");
        }
        this.tenantId = tenantId;
        this.amountMinor = Money.requirePositiveMinor(amountMinor, "amountMinor");
        this.sourceTransactionId = sourceTransactionId;
        contributedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getGoalId() {
        return goalId;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public UUID getSourceTransactionId() {
        return sourceTransactionId;
    }

    public Instant getContributedAt() {
        return contributedAt;
    }
}
