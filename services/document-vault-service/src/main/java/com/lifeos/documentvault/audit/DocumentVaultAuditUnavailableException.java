package com.lifeos.documentvault.audit;

/** A security-relevant outcome could not be durably recorded. */
public class DocumentVaultAuditUnavailableException extends RuntimeException {

    public DocumentVaultAuditUnavailableException() {
        super(null, null, false, false);
    }
}
