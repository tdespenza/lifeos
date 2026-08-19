package com.lifeos.finance.idempotency;

/** Conditional request header is present but malformed, weak, duplicated, or unsupported. */
public class InvalidFinancePreconditionException extends IllegalArgumentException {

    public InvalidFinancePreconditionException() {
        super("Finance conditional request header is invalid");
    }
}
