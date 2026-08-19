package com.lifeos.finance.service;

/** Public mutation response with durable replay metadata. */
public record FinanceMutationResult<T>(T body, boolean replayed, int responseStatus, String responseLocation) {
}
