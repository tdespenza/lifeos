package com.lifeos.taskgoal.goal.idempotency;

/** A client reused one idempotency key for a different goal-create payload. */
public class GoalIdempotencyConflictException extends RuntimeException {

    public GoalIdempotencyConflictException() {
        super("Idempotency key conflicts with an existing request");
    }
}
