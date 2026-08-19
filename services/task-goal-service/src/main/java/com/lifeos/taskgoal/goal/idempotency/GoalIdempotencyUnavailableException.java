package com.lifeos.taskgoal.goal.idempotency;

/** The durable idempotency record cannot be safely completed or replayed right now. */
public class GoalIdempotencyUnavailableException extends RuntimeException {

    public GoalIdempotencyUnavailableException() {
        super("Idempotency request is temporarily unavailable");
    }
}
