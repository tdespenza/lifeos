package com.lifeos.identity.authorization;

/**
 * Bounded denial classifications exposed only to authenticated internal services and redacted
 * audit/metric sinks. These values must never include resource identifiers or free-form input.
 */
public enum AuthorizationDenyReason {

    /** Request shape or resource facts are incomplete, oversized, or invalid. */
    MALFORMED_REQUEST,

    /** The subject/session is missing, revoked, expired, mismatched, or account is inactive. */
    STALE_SUBJECT,

    /** The caller requested a policy version other than the authority's current version. */
    POLICY_VERSION_MISMATCH,

    /** The exact action is not supported by the current policy. */
    UNSUPPORTED_ACTION,

    /** The authenticated workload is not bound to the requested action and resource family. */
    WORKLOAD_NOT_AUTHORIZED,

    /** No scoped role grants the requested action. */
    MISSING_ROLE,

    /** The resource tenant is outside the subject's effective scope. */
    TENANT_MISMATCH,

    /** The resource owner condition does not hold. */
    OWNER_MISMATCH,

    /** A required policy or persistence dependency failed; callers must fail closed. */
    POLICY_UNAVAILABLE
}
