package com.lifeos.calendar.idempotency;

/** Raised when a mutation lacks required strong concurrency preconditions. */
public class CalendarVersionPreconditionRequiredException extends RuntimeException {
}
