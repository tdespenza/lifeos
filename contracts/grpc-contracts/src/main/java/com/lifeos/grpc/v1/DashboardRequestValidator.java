package com.lifeos.grpc.v1;

/** Validates bounded dashboard lookback requests before dispatching fan-out calls. */
public final class DashboardRequestValidator {

    /** Minimum dashboard lookback accepted by the contract. */
    public static final int MINIMUM_PERIOD_DAYS = 1;

    /** Maximum dashboard lookback accepted by the contract. */
    public static final int MAXIMUM_PERIOD_DAYS = 366;

    private DashboardRequestValidator() {
    }

    /**
     * Validates the dashboard lookback range.
     *
     * @param request request to validate
     * @throws IllegalArgumentException when the request is null or outside the bounded range
     */
    public static void validate(ScopedDashboardRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        if (request.getPeriodDays() < MINIMUM_PERIOD_DAYS
                || request.getPeriodDays() > MAXIMUM_PERIOD_DAYS) {
            throw new IllegalArgumentException("period_days must be between 1 and 366");
        }
    }
}
