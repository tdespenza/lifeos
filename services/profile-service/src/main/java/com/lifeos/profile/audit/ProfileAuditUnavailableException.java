package com.lifeos.profile.audit;

/** Fails a security decision closed when its required durable audit outcome cannot be recorded. */
public class ProfileAuditUnavailableException extends RuntimeException {

    public ProfileAuditUnavailableException(Throwable cause) {
        super(cause);
    }
}
