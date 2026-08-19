package com.lifeos.profile.idempotency;

/** Raised for absent, repeated, or malformed idempotency headers. */
public class InvalidProfileIdempotencyKeyException extends RuntimeException {
}
