package com.lifeos.finance.idempotency;

/** Creation requires a conditional request so a client cannot accidentally overwrite a representation. */
public class FinanceCreatePreconditionRequiredException extends RuntimeException {

    public FinanceCreatePreconditionRequiredException() {
        super("If-None-Match: * is required for Finance creation");
    }
}
