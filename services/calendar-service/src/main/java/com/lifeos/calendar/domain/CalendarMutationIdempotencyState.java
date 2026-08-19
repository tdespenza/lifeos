package com.lifeos.calendar.domain;

/** Reservation lifecycle for retry-safe user mutation responses. */
public enum CalendarMutationIdempotencyState {
    PENDING,
    COMPLETED
}
