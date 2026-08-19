package com.lifeos.documentvault.idempotency;

/** Reservation state remains pending until the metadata write and immutable response snapshot commit. */
public enum DocumentCommandIdempotencyState {
    PENDING,
    COMPLETED
}
