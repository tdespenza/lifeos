package com.lifeos.grpc;

import com.google.protobuf.Timestamp;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;

/** Validates required protobuf timestamp ranges before a bounded service query is executed. */
public final class TimestampRangeValidator {

    private static final long MIN_TIMESTAMP_SECONDS = -62_135_596_800L;
    private static final long MAX_TIMESTAMP_SECONDS = 253_402_300_799L;

    private TimestampRangeValidator() {
    }

    /**
     * Validates timestamp presence, protobuf validity, ordering, and a maximum interval.
     *
     * @param startsAt inclusive lower bound
     * @param endsAt exclusive upper bound
     * @param maximumRangeDays maximum permitted interval in days
     * @param rangeName domain label used in validation errors
     * @throws IllegalArgumentException when any range invariant is violated
     */
    public static void validate(
            Timestamp startsAt,
            Timestamp endsAt,
            int maximumRangeDays,
            String rangeName) {
        if (startsAt == null || endsAt == null) {
            throw new IllegalArgumentException("starts_at and ends_at are required");
        }
        if (maximumRangeDays <= 0 || rangeName == null || rangeName.isBlank()) {
            throw new IllegalArgumentException("timestamp range validation configuration is invalid");
        }

        Instant start = toInstant(startsAt, "starts_at");
        Instant end = toInstant(endsAt, "ends_at");
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("ends_at must be strictly after starts_at");
        }
        if (Duration.between(start, end).compareTo(Duration.ofDays(maximumRangeDays)) > 0) {
            throw new IllegalArgumentException(rangeName + " date range must not exceed " + maximumRangeDays + " days");
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
