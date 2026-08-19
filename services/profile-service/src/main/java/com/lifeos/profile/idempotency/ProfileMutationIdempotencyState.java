package com.lifeos.profile.idempotency;

/** Durable reservation state; completed records retain the exact public response snapshot. */
public enum ProfileMutationIdempotencyState {
    PENDING,
    COMPLETED
}
