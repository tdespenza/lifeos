package com.lifeos.identity.auth;

/**
 * Raised when an internal endpoint cannot authenticate the calling workload.
 *
 * <p>The exception deliberately contains no caller identity, token, or configuration detail so
 * error handlers can return one generic response to missing, unknown, and mismatched workloads.
 */
public class InternalWorkloadAuthenticationException extends RuntimeException {

    /**
     * Creates a sanitized workload-authentication failure.
     */
    public InternalWorkloadAuthenticationException() {
        super("The internal caller could not be authenticated.");
    }
}
