package com.lifeos.finance.authorization;

/** Identity validation or decision data could not be safely obtained. */
public class FinanceAuthorizationDependencyUnavailable extends RuntimeException {

    public FinanceAuthorizationDependencyUnavailable() {
        super("Finance authorization dependency unavailable");
    }

    public FinanceAuthorizationDependencyUnavailable(Throwable cause) {
        super("Finance authorization dependency unavailable", cause);
    }
}
