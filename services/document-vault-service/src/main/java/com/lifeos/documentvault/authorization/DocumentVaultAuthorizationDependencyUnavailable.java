package com.lifeos.documentvault.authorization;

/** Identity validation could not safely establish an authenticated subject. */
public class DocumentVaultAuthorizationDependencyUnavailable extends RuntimeException {

    public DocumentVaultAuthorizationDependencyUnavailable() {
        super(null, null, false, false);
    }

    public DocumentVaultAuthorizationDependencyUnavailable(Throwable cause) {
        super(null, cause, false, false);
    }
}
