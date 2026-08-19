package com.lifeos.profile.audit;

/** Bounded redacted audit classifications for security-relevant Profile service decisions. */
public enum ProfileSecurityAuditEventType {
    AUTHENTICATION_FAILED,
    AUTHENTICATION_DEPENDENCY_UNAVAILABLE,
    AUTHORIZATION_ALLOWED,
    AUTHORIZATION_DENIED,
    AUTHORIZATION_DEPENDENCY_UNAVAILABLE,
    HOUSEHOLD_SCOPE_ALLOWED,
    HOUSEHOLD_SCOPE_DENIED,
    MUTATION_COMPLETED,
    MUTATION_REPLAYED
}
