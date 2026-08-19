package com.lifeos.taskgoal.task.idempotency;

/** A Task command retry key was reused with a different canonical request. */
public class TaskIdempotencyConflictException extends RuntimeException {

    public TaskIdempotencyConflictException() {
        super("Idempotency key conflicts with an existing request");
    }
}
