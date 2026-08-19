package com.lifeos.identity.authorization;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable, closed binding between one action, its owning workload, and one resource-fact shape.
 *
 * <p>Descriptors are source-defined rather than configuration-driven. This deliberately prevents
 * a deployment typo, a wildcard, or a newly added enum member from widening authorization.
 *
 * @param action exact closed action
 * @param workloadIdentity exact authenticated workload allowed to request the action
 * @param resourceType exact resource family accepted for the action
 * @param resourceShape trusted resource-fact arrangement required for the action
 * @param tenantScope personal or explicitly scoped tenant boundary
 * @param ownerRule owner/requester predicate after RBAC and tenant evaluation
 */
public record AuthorizationActionDescriptor(
        AuthorizationAction action,
        String workloadIdentity,
        String resourceType,
        AuthorizationResourceShape resourceShape,
        AuthorizationTenantScope tenantScope,
        AuthorizationOwnerRule ownerRule) {

    private static final Pattern IDENTIFIER = Pattern.compile("[a-z][a-z0-9-]{0,63}");

    /** Validates an exact, non-wildcard descriptor at construction time. */
    public AuthorizationActionDescriptor {
        Objects.requireNonNull(action, "action must not be null");
        requireIdentifier(workloadIdentity, "workloadIdentity");
        requireIdentifier(resourceType, "resourceType");
        Objects.requireNonNull(resourceShape, "resourceShape must not be null");
        Objects.requireNonNull(tenantScope, "tenantScope must not be null");
        Objects.requireNonNull(ownerRule, "ownerRule must not be null");
        validateShapeAndOwnerRule(resourceShape, ownerRule);
        if (ownerRule == AuthorizationOwnerRule.SUBJECT_OR_TENANT_ADMIN
                && tenantScope != AuthorizationTenantScope.SCOPED) {
            throw new IllegalArgumentException("tenant-admin access requires an explicitly scoped tenant");
        }
    }

    private static void requireIdentifier(String value, String name) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must be a bounded exact identifier");
        }
    }

    private static void validateShapeAndOwnerRule(
            AuthorizationResourceShape resourceShape, AuthorizationOwnerRule ownerRule) {
        boolean valid = switch (resourceShape) {
            case OWNED_CREATE -> ownerRule == AuthorizationOwnerRule.SUBJECT_ONLY;
            case OWNED_OBJECT -> ownerRule == AuthorizationOwnerRule.SUBJECT_ONLY
                    || ownerRule == AuthorizationOwnerRule.SUBJECT_OR_TENANT_ADMIN;
            case TENANT_COLLECTION -> ownerRule == AuthorizationOwnerRule.NONE;
            case REQUESTER_CAPABILITY -> ownerRule == AuthorizationOwnerRule.REQUESTER_SUBJECT;
        };
        if (!valid) {
            throw new IllegalArgumentException("owner rule is not valid for the resource shape");
        }
    }
}
