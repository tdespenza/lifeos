package com.lifeos.calendar.idempotency;

/** Stable scopes used in the durable Calendar idempotency table. */
public enum CalendarMutationOperation {
    EVENT_CREATE,
    EVENT_UPDATE,
    EVENT_CANCEL,
    TIME_BLOCK_CREATE,
    TIME_BLOCK_UPDATE,
    TIME_BLOCK_CANCEL
}
