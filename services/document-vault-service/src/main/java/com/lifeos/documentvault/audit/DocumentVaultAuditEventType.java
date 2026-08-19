package com.lifeos.documentvault.audit;

/** Closed, redacted audit classifications for document actions and owner-scope denials. */
public enum DocumentVaultAuditEventType {
    AUTHORIZATION_ALLOWED,
    AUTHORIZATION_DENIED,
    AUTHORIZATION_DEPENDENCY_UNAVAILABLE,
    DOCUMENT_CREATED,
    DOCUMENT_METADATA_UPDATED,
    DOCUMENT_READ,
    DOCUMENT_SEARCHED,
    DOCUMENT_RESOURCE_UNAVAILABLE
}
