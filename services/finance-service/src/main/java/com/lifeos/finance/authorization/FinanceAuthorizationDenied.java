package com.lifeos.finance.authorization;

/** Identity issued a usable, explicit denial for this request. */
public class FinanceAuthorizationDenied extends RuntimeException {

    public FinanceAuthorizationDenied() {
        super("Finance authorization denied");
    }
}
