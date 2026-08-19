package com.lifeos.documentvault.authorization;

/** The inbound bearer is absent, malformed, or rejected by identity-service. */
public class DocumentVaultAuthenticationFailure extends RuntimeException {

    public DocumentVaultAuthenticationFailure() {
        super(null, null, false, false);
    }

    public DocumentVaultAuthenticationFailure(Throwable cause) {
        super(null, cause, false, false);
    }
}
