package com.lifeos.calendar.idempotency;

import java.util.List;

/** Parses a strong integer ETag for optimistic lifecycle mutations. */
public final class CalendarVersionPrecondition {

    public static final String HEADER_NAME = "If-Match";

    private CalendarVersionPrecondition() {
    }

    public static long requireSingleHeader(List<String> values) {
        if (values == null || values.size() != 1 || values.getFirst() == null) {
            throw new CalendarVersionPreconditionRequiredException();
        }
        String raw = values.getFirst().trim();
        if (raw.length() < 3 || raw.charAt(0) != '"' || raw.charAt(raw.length() - 1) != '"') {
            throw new InvalidCalendarVersionPreconditionException();
        }
        String value = raw.substring(1, raw.length() - 1);
        if (!value.matches("0|[1-9][0-9]{0,18}")) {
            throw new InvalidCalendarVersionPreconditionException();
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new InvalidCalendarVersionPreconditionException();
        }
    }
}
