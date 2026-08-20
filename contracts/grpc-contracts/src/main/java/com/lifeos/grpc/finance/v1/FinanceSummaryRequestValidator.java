package com.lifeos.grpc.finance.v1;

import com.google.protobuf.Timestamp;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;

/** Validates bounded finance period requests before transaction history is queried. */
public final class FinanceSummaryRequestValidator {

    /** Maximum inclusive date-range duration accepted by the finance summary contract. */
    public static final int MAXIMUM_RANGE_DAYS = 366;

    private static final long MIN_TIMESTAMP_SECONDS = -62_135_596_800L;
    private static final long MAX_TIMESTAMP_SECONDS = 253_402_300_799L;

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
        if (!request.hasStartsAt() || !request.hasEndsAt()) {
            throw new IllegalArgumentException("starts_at and ends_at are required");
        }

        Instant startsAt = toInstant(request.getStartsAt(), "starts_at");
        Instant endsAt = toInstant(request.getEndsAt(), "ends_at");
        if (!endsAt.isAfter(startsAt)) {
            throw new IllegalArgumentException("ends_at must be strictly after starts_at");
        }
        if (Duration.between(startsAt, endsAt).compareTo(Duration.ofDays(MAXIMUM_RANGE_DAYS)) > 0) {
            throw new IllegalArgumentException("finance summary date range must not exceed 366 days");
        }
    }

    private static Instant toInstant(Timestamp timestamp, String field) {
        if (timestamp.getSeconds() < MIN_TIMESTAMP_SECONDS
                || timestamp.getSeconds() > MAX_TIMESTAMP_SECONDS
                || timestamp.getNanos() < 0
                || timestamp.getNanos() > 999_999_999) {
            throw new IllegalArgumentException(field + " must be a valid protobuf timestamp");
        }
        try {
            return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
        } catch (DateTimeException | ArithmeticException exception) {
            throw new IllegalArgumentException(field + " must be a valid protobuf timestamp", exception);
        }
    }
}
