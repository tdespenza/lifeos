package com.lifeos.taskgoal.task.idempotency;

/** A Task mutation supplied a weak, wildcard, duplicate, or malformed If-Match value. */
public class InvalidTaskVersionPreconditionException extends RuntimeException {
}
