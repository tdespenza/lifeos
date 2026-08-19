package com.lifeos.taskgoal.goal.idempotency;

/** Durable state for a goal-create idempotency request. */
public enum GoalCreationIdempotencyState {
    /** The key has reserved a goal identifier but its goal transaction did not commit yet. */
    PENDING,

    /** The corresponding goal and replay result are committed. */
    COMPLETED
}
