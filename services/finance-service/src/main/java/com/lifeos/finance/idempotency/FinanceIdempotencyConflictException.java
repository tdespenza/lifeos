package com.lifeos.finance.idempotency;

/** A caller reused an idempotency key for a different logical mutation. */
public class FinanceIdempotencyConflictException extends RuntimeException {

    public FinanceIdempotencyConflictException() {
        super("Finance idempotency key conflicts with an existing request");
    }
}
