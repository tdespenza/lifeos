package com.lifeos.identity.account;

/**
 * Indicates that an account cannot be created because its email is already registered.
 *
 * <p>The message intentionally does not include the email address so it can be returned to clients
 * without disclosing submitted personal data.
 */
public class EmailAlreadyRegisteredException extends RuntimeException {

    /**
     * Creates a sanitized duplicate-registration exception.
     */
    public EmailAlreadyRegisteredException() {
        super("An account already exists for the supplied email address.");
    }
}
