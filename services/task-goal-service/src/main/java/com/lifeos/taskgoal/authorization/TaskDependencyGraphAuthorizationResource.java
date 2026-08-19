package com.lifeos.taskgoal.authorization;

import java.util.Map;

/** Tenant collection facts for the exact v2 persisted Task/Goal dependency graph actions. */
public record TaskDependencyGraphAuthorizationResource(
        String resourceType, String resourceId, String tenantId, Map<String, String> attributes)
        implements TaskGoalAuthorizationResource {

    private static final String RESOURCE_TYPE = "task-dependency-graph";

    public TaskDependencyGraphAuthorizationResource {
        if (!RESOURCE_TYPE.equals(resourceType)) {
            throw new IllegalArgumentException("resourceType must be task-dependency-graph");
        }
        if (resourceId != null || tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("dependency graph resource must be a tenant collection");
        }
        attributes = Map.copyOf(attributes == null ? Map.of() : attributes);
        if (!attributes.isEmpty()) {
            throw new IllegalArgumentException("dependency graph collection must not contain attributes");
        }
    }

    public static TaskDependencyGraphAuthorizationResource forCollection(String tenantId) {
        return new TaskDependencyGraphAuthorizationResource(RESOURCE_TYPE, null, tenantId, Map.of());
    }
}
