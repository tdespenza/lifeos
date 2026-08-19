package com.lifeos.finance.audit;

/** Bounded, redacted classifications for security-relevant Finance service decisions. */
public enum FinanceSecurityAuditEventType {
    AUTHENTICATION_FAILED,
    AUTHENTICATION_DEPENDENCY_UNAVAILABLE,
    AUTHORIZATION_ALLOWED,
    AUTHORIZATION_DENIED,
    AUTHORIZATION_DEPENDENCY_UNAVAILABLE,
    MUTATION_COMPLETED,
    MUTATION_REPLAYED
}
