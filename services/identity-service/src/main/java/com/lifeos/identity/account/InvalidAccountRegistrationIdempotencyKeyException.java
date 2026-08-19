package com.lifeos.identity.account;

/** A registration request omitted, duplicated, or malformed its required idempotency key. */
public class InvalidAccountRegistrationIdempotencyKeyException extends RuntimeException {

    public InvalidAccountRegistrationIdempotencyKeyException() {
        super("A valid Idempotency-Key header is required");
    }
}
