package com.lifeos.documentvault.idempotency;

/** A caller reused a scoped idempotency key for a different request. */
public class DocumentIdempotencyConflictException extends RuntimeException {
}
