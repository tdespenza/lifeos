package com.lifeos.identity.account;

/** Durable state for a public account-registration request. */
public enum AccountRegistrationIdempotencyState {
    /** A key was reserved but its account-and-credential transaction has not committed. */
    PENDING,

    /** The account, credential, audit record, and replay mapping committed together. */
    COMPLETED
}
