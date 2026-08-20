package com.lifeos.grpc.contracts;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.protobuf.Timestamp;
import com.lifeos.grpc.calendar.v1.CalendarAvailabilityRequestValidator;
import com.lifeos.grpc.calendar.v1.FindBusyIntervalsRequest;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class CalendarAvailabilityRequestValidatorTest {

    @Test
    void acceptsAValidBoundedRange() {
        assertDoesNotThrow(() -> CalendarAvailabilityRequestValidator.validate(
                request("2026-01-01T00:00:00Z", "2026-02-01T00:00:00Z")));
    }

    @Test
    void rejectsMissingBounds() {
        assertThrows(IllegalArgumentException.class, () -> CalendarAvailabilityRequestValidator.validate(
                FindBusyIntervalsRequest.newBuilder().setOwnerAccountId("account-1").build()));
    }

    @Test
    void rejectsEqualAndReversedBounds() {
        assertThrows(IllegalArgumentException.class, () -> CalendarAvailabilityRequestValidator.validate(
                request("2026-01-02T00:00:00Z", "2026-01-02T00:00:00Z")));
        assertThrows(IllegalArgumentException.class, () -> CalendarAvailabilityRequestValidator.validate(
                request("2026-01-03T00:00:00Z", "2026-01-02T00:00:00Z")));
    }

    @Test
    void rejectsInvalidTimestampBounds() {
        FindBusyIntervalsRequest request = FindBusyIntervalsRequest.newBuilder()
                .setStartsAt(Timestamp.newBuilder().setSeconds(0).setNanos(1_000_000_000).build())
                .setEndsAt(timestamp("2026-01-02T00:00:00Z"))
                .build();

        assertThrows(IllegalArgumentException.class, () -> CalendarAvailabilityRequestValidator.validate(request));
    }

    @Test
    void rejectsAnOverlongRange() {
        assertThrows(IllegalArgumentException.class, () -> CalendarAvailabilityRequestValidator.validate(
                request("2026-01-01T00:00:00Z", "2026-02-02T00:00:00Z")));
    }

    private static FindBusyIntervalsRequest request(String startsAt, String endsAt) {
        return FindBusyIntervalsRequest.newBuilder()
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
