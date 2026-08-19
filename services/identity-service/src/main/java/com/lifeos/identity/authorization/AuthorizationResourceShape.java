package com.lifeos.identity.authorization;

/**
 * Closed shapes for trusted resource facts accepted by the authorization decision boundary.
 *
 * <p>The protected service always loads these facts from its own system of record. A shape is
 * intentionally narrower than a resource family: adding a new action cannot silently accept a
 * new collection or attribute arrangement.
 */
public enum AuthorizationResourceShape {

    /** A new UUID-addressed resource with exactly one {@code ownerAccountId} fact. */
    OWNED_CREATE,

    /** An existing UUID-addressed resource with owner and existence facts. */
    OWNED_OBJECT,

    /** A tenant collection with no resource identifier or attributes. */
    TENANT_COLLECTION,

    /** A UUID-addressed local capability with exactly one {@code requesterAccountId} fact. */
    REQUESTER_CAPABILITY
}
