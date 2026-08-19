package com.lifeos.finance.idempotency;

/** Caller supplied no valid, bounded idempotency key. */
public class InvalidFinanceIdempotencyKeyException extends IllegalArgumentException {

    public InvalidFinanceIdempotencyKeyException() {
        super("A valid Finance Idempotency-Key is required");
    }
}
