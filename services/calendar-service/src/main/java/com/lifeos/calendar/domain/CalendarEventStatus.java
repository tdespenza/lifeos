package com.lifeos.calendar.domain;

/** Explicit event lifecycle; cancelled rows remain for safe idempotency and audit history. */
public enum CalendarEventStatus {
    ACTIVE,
    CANCELLED
}
