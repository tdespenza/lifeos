package com.lifeos.taskgoal.task.idempotency;

/** Durable state for one Task command reservation. */
public enum TaskCommandIdempotencyState {
    PENDING,
    COMPLETED
}
