package com.lifeos.calendar.idempotency;

/** Typed successful mutation result, including whether it was replayed from a durable snapshot. */
public record CalendarIdempotencyResult<T>(T body, int status, String location, boolean replayed) {
}
