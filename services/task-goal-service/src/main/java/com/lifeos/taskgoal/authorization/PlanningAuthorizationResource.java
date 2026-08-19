package com.lifeos.taskgoal.authorization;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Strict owner facts for habit, routine, and milestone authorization decisions. */
public record PlanningAuthorizationResource(
        String resourceType, String resourceId, String tenantId, Map<String, String> attributes)
        implements TaskGoalAuthorizationResource {

    private static final Set<String> RESOURCE_TYPES = Set.of("habit", "routine", "milestone");
    private static final String OWNER_ACCOUNT_ID = "ownerAccountId";
    private static final String RESOURCE_EXISTS = "resourceExists";
    private static final String MISSING_OWNER_ACCOUNT_ID = "00000000-0000-0000-0000-000000000000";

    public PlanningAuthorizationResource {
        if (!RESOURCE_TYPES.contains(resourceType)) {
            throw new IllegalArgumentException("unsupported planning resource type");
        }
        if (tenantId == null || tenantId.isBlank() || tenantId.length() > 255) {
            throw new IllegalArgumentException("tenantId must be bounded and non-blank");
        }
        attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes must not be null"));
        if (resourceId == null) {
            if (!attributes.isEmpty()) {
                throw new IllegalArgumentException("collection resources must not contain attributes");
            }
        } else {
            UUID.fromString(resourceId);
            if (attributes.get(OWNER_ACCOUNT_ID) == null) {
                throw new IllegalArgumentException("ownerAccountId must be present for object resources");
            }
            if (!attributes.keySet().equals(Set.of(OWNER_ACCOUNT_ID))
                    && !attributes.keySet().equals(Set.of(OWNER_ACCOUNT_ID, RESOURCE_EXISTS))) {
                throw new IllegalArgumentException("planning facts have an unsupported shape");
            }
            if (attributes.containsKey(RESOURCE_EXISTS)
                    && !Set.of("true", "false").contains(attributes.get(RESOURCE_EXISTS))) {
                throw new IllegalArgumentException("resourceExists must be boolean");
            }
        }
    }

    public static PlanningAuthorizationResource forNew(
            String resourceType, UUID resourceId, UUID ownerAccountId, String tenantId) {
        return new PlanningAuthorizationResource(
                resourceType,
                Objects.requireNonNull(resourceId, "resourceId must not be null").toString(),
                tenantId,
                Map.of(OWNER_ACCOUNT_ID,
                        Objects.requireNonNull(ownerAccountId, "ownerAccountId must not be null").toString()));
    }

    public static PlanningAuthorizationResource forExisting(
            String resourceType, UUID resourceId, UUID ownerAccountId, String tenantId) {
        return object(resourceType, resourceId, ownerAccountId, tenantId, true);
    }

    public static PlanningAuthorizationResource forMissing(
            String resourceType, UUID resourceId, String tenantId) {
        return object(resourceType, resourceId, UUID.fromString(MISSING_OWNER_ACCOUNT_ID), tenantId, false);
    }

    public static PlanningAuthorizationResource forCollection(String resourceType, String tenantId) {
        return new PlanningAuthorizationResource(resourceType, null, tenantId, Map.of());
    }

    private static PlanningAuthorizationResource object(
            String resourceType, UUID resourceId, UUID ownerAccountId, String tenantId, boolean exists) {
        return new PlanningAuthorizationResource(
                resourceType,
                Objects.requireNonNull(resourceId, "resourceId must not be null").toString(),
                tenantId,
                Map.of(OWNER_ACCOUNT_ID,
                        Objects.requireNonNull(ownerAccountId, "ownerAccountId must not be null").toString(),
                        RESOURCE_EXISTS,
                        Boolean.toString(exists)));
    }
}
