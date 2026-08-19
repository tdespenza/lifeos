package com.lifeos.taskgoal.goal.idempotency;

/** Durable state for one non-create goal mutation request. */
public enum GoalMutationIdempotencyState {
    /** The key is reserved, but its lifecycle write has not committed. */
    PENDING,

    /** The lifecycle write and immutable replay snapshot committed together. */
    COMPLETED
}
