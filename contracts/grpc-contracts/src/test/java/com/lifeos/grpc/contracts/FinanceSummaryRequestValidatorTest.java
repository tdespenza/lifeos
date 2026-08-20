package com.lifeos.grpc.contracts;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.protobuf.Timestamp;
import com.lifeos.grpc.finance.v1.FinanceSummaryRequestValidator;
import com.lifeos.grpc.finance.v1.GetPeriodSummaryRequest;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class FinanceSummaryRequestValidatorTest {

    @Test
    void acceptsTheMaximumBoundedRange() {
        assertDoesNotThrow(() -> FinanceSummaryRequestValidator.validate(request(
                "2026-01-01T00:00:00Z", "2027-01-02T00:00:00Z")));
    }

    @Test
    void rejectsMissingBounds() {
        assertThrows(IllegalArgumentException.class, () -> FinanceSummaryRequestValidator.validate(
                GetPeriodSummaryRequest.newBuilder().setOwnerAccountId("account-1").build()));
    }

    @Test
    void rejectsNonIncreasingBounds() {
        assertThrows(IllegalArgumentException.class, () -> FinanceSummaryRequestValidator.validate(
                request("2026-01-02T00:00:00Z", "2026-01-02T00:00:00Z")));
        assertThrows(IllegalArgumentException.class, () -> FinanceSummaryRequestValidator.validate(
                request("2026-01-03T00:00:00Z", "2026-01-02T00:00:00Z")));
    }

    @Test
    void rejectsAnOverlongRange() {
        assertThrows(IllegalArgumentException.class, () -> FinanceSummaryRequestValidator.validate(
                request("2026-01-01T00:00:00Z", "2027-01-03T00:00:00Z")));
    }

    @Test
    void rejectsInvalidProtobufTimestampValues() {
        GetPeriodSummaryRequest request = GetPeriodSummaryRequest.newBuilder()
                .setStartsAt(Timestamp.newBuilder().setSeconds(0).setNanos(1_000_000_000).build())
                .setEndsAt(timestamp("2026-01-02T00:00:00Z"))
                .build();

        assertThrows(IllegalArgumentException.class, () -> FinanceSummaryRequestValidator.validate(request));
    }

    private static GetPeriodSummaryRequest request(String startsAt, String endsAt) {
        return GetPeriodSummaryRequest.newBuilder()
                .setOwnerAccountId("account-1")
                .setStartsAt(timestamp(startsAt))
                .setEndsAt(timestamp(endsAt))
                .build();
    }

    private static Timestamp timestamp(String value) {
        Instant instant = Instant.parse(value);
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }
}
