package com.lifeos.taskgoal.planning;

public class PlanningIdempotencyConflictException extends RuntimeException {
    public PlanningIdempotencyConflictException() {
        super("Idempotency key conflicts with an existing planning command");
    }
}
