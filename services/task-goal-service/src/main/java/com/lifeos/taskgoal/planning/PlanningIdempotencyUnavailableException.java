package com.lifeos.taskgoal.planning;

public class PlanningIdempotencyUnavailableException extends RuntimeException {
    public PlanningIdempotencyUnavailableException() {
        super("Planning idempotency storage is temporarily unavailable");
    }

    public PlanningIdempotencyUnavailableException(Throwable cause) {
        super("Planning idempotency storage is temporarily unavailable", cause);
    }
}
