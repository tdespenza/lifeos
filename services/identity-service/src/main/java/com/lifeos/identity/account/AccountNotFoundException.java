package com.lifeos.identity.account;

/** A requested account is not available to the authenticated caller. */
public class AccountNotFoundException extends RuntimeException {

    public AccountNotFoundException() {
        super("The requested account is not available.");
    }
}
