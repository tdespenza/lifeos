package com.lifeos.identity.account;

/** Durable registration idempotency state cannot be safely reserved or replayed right now. */
public class AccountRegistrationIdempotencyUnavailableException extends RuntimeException {

    public AccountRegistrationIdempotencyUnavailableException() {
        super("Registration is temporarily unavailable");
    }
}
