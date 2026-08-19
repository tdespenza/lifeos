package com.lifeos.taskgoal.goal.idempotency;

/** Lifecycle command covered by one durable goal-mutation idempotency reservation. */
public enum GoalMutationOperation {
    UPDATE,
    COMPLETE,
    ARCHIVE
}
