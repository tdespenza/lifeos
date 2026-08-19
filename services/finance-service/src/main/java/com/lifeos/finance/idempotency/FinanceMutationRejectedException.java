package com.lifeos.finance.idempotency;

/** A deterministic business precondition rejected a write, so its pending reservation is removed. */
public class FinanceMutationRejectedException extends RuntimeException {

    public FinanceMutationRejectedException(String message) {
        super(message);
    }

    public FinanceMutationRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
