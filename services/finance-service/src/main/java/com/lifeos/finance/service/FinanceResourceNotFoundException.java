package com.lifeos.finance.service;

/** Generic local resource outcome shared by absent and cross-account resource identifiers. */
public class FinanceResourceNotFoundException extends RuntimeException {

    public FinanceResourceNotFoundException() {
        super("Finance resource not found");
    }
}
