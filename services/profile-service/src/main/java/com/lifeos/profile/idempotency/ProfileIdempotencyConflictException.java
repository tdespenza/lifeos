package com.lifeos.profile.idempotency;

/** Raised when a caller reuses a retry key for a semantically different request. */
public class ProfileIdempotencyConflictException extends RuntimeException {
}
