package com.lifeos.finance.idempotency;

/** The durable retry reservation cannot be safely read, written, or replayed. */
public class FinanceIdempotencyUnavailableException extends RuntimeException {

    public FinanceIdempotencyUnavailableException() {
        super("Finance idempotency storage unavailable");
    }

    public FinanceIdempotencyUnavailableException(Throwable cause) {
        super("Finance idempotency storage unavailable", cause);
    }
}
