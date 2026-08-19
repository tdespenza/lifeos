package com.lifeos.taskgoal.task.idempotency;

/** A Task command omitted, duplicated, or malformed its retry key. */
public class InvalidTaskIdempotencyKeyException extends RuntimeException {

    public InvalidTaskIdempotencyKeyException() {
        super("A valid Idempotency-Key header is required");
    }
}
