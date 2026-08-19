package com.lifeos.finance.idempotency;

/** Closed operation scope for Finance durable idempotency reservations. */
public enum FinanceMutationOperation {
    CREATE_BUDGET,
    UPDATE_BUDGET,
    CREATE_TRANSACTION,
    CATEGORIZE_TRANSACTION,
    CREATE_GOAL,
    UPDATE_GOAL,
    CONTRIBUTE_GOAL
}
