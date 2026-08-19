package com.lifeos.identity.account;

/** A client reused a registration idempotency key with a different request payload. */
public class AccountRegistrationIdempotencyConflictException extends RuntimeException {

    public AccountRegistrationIdempotencyConflictException() {
        super("Idempotency key conflicts with an existing registration request");
    }
}
