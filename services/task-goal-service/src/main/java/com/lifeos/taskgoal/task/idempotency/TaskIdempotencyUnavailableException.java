package com.lifeos.taskgoal.task.idempotency;

/** A durable Task command reservation cannot safely be read or completed right now. */
public class TaskIdempotencyUnavailableException extends RuntimeException {

    public TaskIdempotencyUnavailableException() {
        super("Idempotency request is temporarily unavailable");
    }
}
