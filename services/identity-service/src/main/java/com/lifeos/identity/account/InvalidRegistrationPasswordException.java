package com.lifeos.identity.account;

/** A public-registration password did not satisfy the bounded local policy. */
public class InvalidRegistrationPasswordException extends RuntimeException {

    public InvalidRegistrationPasswordException() {
        super("The registration password does not meet the required policy");
    }
}
