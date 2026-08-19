package com.lifeos.profile.idempotency;

/** Raised when a creation endpoint receives a non-create-only conditional header. */
public class InvalidProfileCreatePreconditionException extends RuntimeException {
}
