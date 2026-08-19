package com.lifeos.taskgoal.task.idempotency;

/** One retryable Task command represented by a durable idempotency reservation. */
public enum TaskCommandOperation {
    CREATE,
    UPDATE,
    COMPLETE,
    CANCEL
}
