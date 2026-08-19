package com.lifeos.calendar.domain;

/** Durable reminder progression from scheduled local work to a producer outbox record. */
public enum CalendarReminderState {
    SCHEDULED,
    LEASED,
    OUTBOXED,
    CANCELLED,
    EXPIRED
}
