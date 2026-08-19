package com.lifeos.taskgoal.goal.idempotency;

/** The required idempotency header is absent, duplicated, or outside the bounded key contract. */
public class InvalidGoalIdempotencyKeyException extends RuntimeException {

    public InvalidGoalIdempotencyKeyException() {
        super("A valid Idempotency-Key header is required");
    }
}
