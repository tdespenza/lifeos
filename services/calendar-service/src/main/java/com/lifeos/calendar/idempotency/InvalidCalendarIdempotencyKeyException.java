package com.lifeos.calendar.idempotency;

/** Raised when a required idempotency key is absent, duplicated, or outside the accepted grammar. */
public class InvalidCalendarIdempotencyKeyException extends RuntimeException {
}
