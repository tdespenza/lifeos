package com.lifeos.profile.idempotency;

/** Raised when a creation endpoint omits If-None-Match: *. */
public class ProfileCreatePreconditionRequiredException extends RuntimeException {
}
