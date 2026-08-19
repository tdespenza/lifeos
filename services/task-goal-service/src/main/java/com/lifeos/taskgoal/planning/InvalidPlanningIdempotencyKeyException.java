package com.lifeos.taskgoal.planning;

public class InvalidPlanningIdempotencyKeyException extends RuntimeException {
    public InvalidPlanningIdempotencyKeyException() {
        super("A bounded ASCII Idempotency-Key header is required");
    }
}
