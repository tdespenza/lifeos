package com.lifeos.calendar.idempotency;

import java.util.List;

/** Strict bounded idempotency-key parser. */
public final class CalendarIdempotencyKey {

    public static final String HEADER_NAME = "Idempotency-Key";

    private CalendarIdempotencyKey() {
    }

    public static String requireSingleHeader(List<String> values) {
        if (values == null || values.size() != 1) {
            throw new InvalidCalendarIdempotencyKeyException();
        }
        String key = values.getFirst();
        if (key == null || !key.matches("[A-Za-z0-9][A-Za-z0-9._~-]{15,127}")) {
            throw new InvalidCalendarIdempotencyKeyException();
        }
        return key;
    }
}
