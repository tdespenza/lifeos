package com.lifeos.finance.authorization;

/** Inbound bearer is absent, malformed, or rejected by Identity. */
public class FinanceAuthenticationFailure extends RuntimeException {

    public FinanceAuthenticationFailure() {
        super("Finance authentication failed");
    }

    public FinanceAuthenticationFailure(Throwable cause) {
        super("Finance authentication failed", cause);
    }
}
