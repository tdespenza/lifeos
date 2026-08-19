package com.lifeos.documentvault.authorization;

/** Identity made a deterministic deny decision for an exact V2 Document Vault action. */
public class DocumentVaultAuthorizationDenied extends RuntimeException {

    public DocumentVaultAuthorizationDenied() {
        super(null, null, false, false);
    }
}
