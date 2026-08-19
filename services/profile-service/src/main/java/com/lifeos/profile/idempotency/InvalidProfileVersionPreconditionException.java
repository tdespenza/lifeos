package com.lifeos.profile.idempotency;

/** Raised when an If-Match header is not one strong numeric ETag. */
public class InvalidProfileVersionPreconditionException extends RuntimeException {
}
