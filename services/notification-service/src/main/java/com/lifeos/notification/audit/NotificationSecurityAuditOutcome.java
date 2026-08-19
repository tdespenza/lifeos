package com.lifeos.notification.audit;

/** Safe, low-cardinality result for a security-relevant notification action. */
public enum NotificationSecurityAuditOutcome {
    SUCCESS,
    DENIED,
    UNAVAILABLE
}
