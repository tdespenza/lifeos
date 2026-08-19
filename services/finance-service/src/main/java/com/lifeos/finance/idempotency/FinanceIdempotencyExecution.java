package com.lifeos.finance.idempotency;

/** Immutable mutation result or exact response replay metadata. */
public record FinanceIdempotencyExecution<T>(T body, boolean replayed, int responseStatus, String responseLocation) {
}
