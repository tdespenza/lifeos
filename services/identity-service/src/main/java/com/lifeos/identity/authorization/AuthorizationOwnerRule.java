package com.lifeos.identity.authorization;

/** Closed owner or requester predicate attached to a resource-fact shape. */
public enum AuthorizationOwnerRule {

    /** The persisted owner must be the verified subject. */
    SUBJECT_ONLY,

    /** The persisted owner must be the subject, unless a scoped tenant admin is permitted. */
    SUBJECT_OR_TENANT_ADMIN,

    /** The trusted local-capability requester must be the verified subject. */
    REQUESTER_SUBJECT,

    /** No owner or requester fact is meaningful for this collection action. */
    NONE
}
