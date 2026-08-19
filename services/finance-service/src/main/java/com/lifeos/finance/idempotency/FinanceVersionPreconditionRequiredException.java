package com.lifeos.finance.idempotency;

/** Existing resource mutation requires a strong ETag. */
public class FinanceVersionPreconditionRequiredException extends RuntimeException {

    public FinanceVersionPreconditionRequiredException() {
        super("If-Match is required for Finance mutation");
    }
}
