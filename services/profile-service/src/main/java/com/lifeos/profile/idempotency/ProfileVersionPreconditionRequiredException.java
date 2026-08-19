package com.lifeos.profile.idempotency;

/** Raised when an existing representation mutation omits its required strong ETag. */
public class ProfileVersionPreconditionRequiredException extends RuntimeException {
}
