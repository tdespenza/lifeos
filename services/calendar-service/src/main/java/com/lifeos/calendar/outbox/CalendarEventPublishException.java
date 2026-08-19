package com.lifeos.calendar.outbox;

/** Safe producer boundary failure; detailed broker errors are never persisted to user-visible data. */
public class CalendarEventPublishException extends RuntimeException {
    public CalendarEventPublishException(Throwable cause) {
        super(cause);
    }
}
