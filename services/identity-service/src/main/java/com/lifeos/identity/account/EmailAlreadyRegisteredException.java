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

    /**
     * Creates a sanitized duplicate-registration exception while preserving the persistence cause
     * for internal diagnostics.
     *
     * @param cause underlying database exception
     */
    public EmailAlreadyRegisteredException(Throwable cause) {
        super("An account already exists for the supplied email address.", cause);
    }
}
