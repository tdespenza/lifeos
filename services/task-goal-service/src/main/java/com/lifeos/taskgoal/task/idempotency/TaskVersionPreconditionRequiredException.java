package com.lifeos.taskgoal.task.idempotency;

/** A non-create Task lifecycle mutation did not include a strong If-Match value. */
public class TaskVersionPreconditionRequiredException extends RuntimeException {
}
