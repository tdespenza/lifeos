package com.lifeos.calendar.outbox;

/** A contract serialization fault is permanent and must roll back the reminder claim. */
public class CalendarOutboxSerializationException extends RuntimeException {
    public CalendarOutboxSerializationException(Throwable cause) {
        super(cause);
    }
}
