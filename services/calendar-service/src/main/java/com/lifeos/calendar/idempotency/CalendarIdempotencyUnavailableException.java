package com.lifeos.calendar.idempotency;

/** Raised when a durable reservation is still in progress or cannot be safely resolved. */
public class CalendarIdempotencyUnavailableException extends RuntimeException {
    public CalendarIdempotencyUnavailableException() {
    }

    public CalendarIdempotencyUnavailableException(Throwable cause) {
        super(cause);
    }
}
