package com.lifeos.finance.idempotency;

/** A write reservation is either pending completion or contains its immutable response snapshot. */
public enum FinanceMutationIdempotencyState {
    PENDING,
    COMPLETED
}
