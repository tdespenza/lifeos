package com.lifeos.grpc.calendar.v1;

import com.lifeos.grpc.TimestampRangeValidator;

/** Validates bounded calendar availability requests before calendar data is queried. */
public final class CalendarAvailabilityRequestValidator {

    /** Maximum interval accepted by the calendar availability contract. */
    public static final int MAXIMUM_RANGE_DAYS = 31;

    private CalendarAvailabilityRequestValidator() {
    }

    /**
     * Validates required timestamp bounds and the maximum calendar query interval.
     *
     * @param request request to validate
     * @throws IllegalArgumentException when the request or range is invalid
     */
    public static void validate(FindBusyIntervalsRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        TimestampRangeValidator.validate(
                request.hasStartsAt() ? request.getStartsAt() : null,
                request.hasEndsAt() ? request.getEndsAt() : null,
                MAXIMUM_RANGE_DAYS,
                "calendar availability");
    }
}
