package com.lifeos.calendar.domain;

/** Durable Kafka relay lifecycle. Dead-lettered records remain available for audited redrive. */
public enum CalendarOutboxState {
    PENDING,
    IN_FLIGHT,
    PUBLISHED,
    DEAD_LETTER,
    CANCELLED
}
