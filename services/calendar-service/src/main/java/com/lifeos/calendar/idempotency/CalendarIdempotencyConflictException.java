package com.lifeos.calendar.idempotency;

/** Raised when one key is reused for different mutation semantics. */
public class CalendarIdempotencyConflictException extends RuntimeException {
}
