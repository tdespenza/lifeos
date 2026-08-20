package com.lifeos.grpc.finance.v1;

import com.lifeos.grpc.TimestampRangeValidator;

/** Validates bounded finance period requests before transaction history is queried. */
public final class FinanceSummaryRequestValidator {

    /** Maximum inclusive date-range duration accepted by the finance summary contract. */
    public static final int MAXIMUM_RANGE_DAYS = 366;

    private FinanceSummaryRequestValidator() {
    }

    /**
     * Validates required timestamps, ordering, and the maximum query interval.
     *
     * <p>Call this method before reading transaction history so invalid requests fail without
     * performing an unbounded database query.
     *
     * @param request request to validate
     * @throws IllegalArgumentException when the request or date range is invalid
     */
    public static void validate(GetPeriodSummaryRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        TimestampRangeValidator.validate(
                request.hasStartsAt() ? request.getStartsAt() : null,
                request.hasEndsAt() ? request.getEndsAt() : null,
                MAXIMUM_RANGE_DAYS,
                "finance summary");
    }
}
