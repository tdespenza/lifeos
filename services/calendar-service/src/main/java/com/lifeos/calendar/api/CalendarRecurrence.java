package com.lifeos.calendar.api;

/** Canonical serialization/parser for the bounded recurrence DTO stored by Calendar. */
public final class CalendarRecurrence {

    private CalendarRecurrence() {
    }

    public static String toStored(CalendarRecurrenceRequest request) {
        if (request == null) {
            return null;
        }
        return request.frequency().name() + ";INTERVAL=" + request.interval() + ";COUNT=" + request.count();
    }

    public static CalendarRecurrenceRequest fromStored(String value) {
        if (value == null) {
            return null;
        }
        String[] parts = value.split(";");
        if (parts.length != 3 || !parts[1].startsWith("INTERVAL=") || !parts[2].startsWith("COUNT=")) {
            throw new IllegalStateException("stored calendar recurrence is invalid");
        }
        try {
            return new CalendarRecurrenceRequest(
                    CalendarRecurrenceFrequency.valueOf(parts[0]),
                    Integer.parseInt(parts[1].substring("INTERVAL=".length())),
                    Integer.valueOf(parts[2].substring("COUNT=".length())));
        } catch (RuntimeException exception) {
            throw new IllegalStateException("stored calendar recurrence is invalid", exception);
        }
    }
}
