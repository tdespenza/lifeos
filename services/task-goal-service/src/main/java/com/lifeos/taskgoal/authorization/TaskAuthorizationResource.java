package com.lifeos.taskgoal.authorization;

import com.lifeos.taskgoal.task.Task;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Trusted local Task facts for the v2 identity authorization contract. */
public record TaskAuthorizationResource(
        String resourceType, String resourceId, String tenantId, Map<String, String> attributes)
        implements TaskGoalAuthorizationResource {

    private static final String RESOURCE_TYPE = "task";
    private static final String OWNER_ACCOUNT_ID = "ownerAccountId";
    private static final String RESOURCE_EXISTS = "resourceExists";
    private static final String MISSING_OWNER_ACCOUNT_ID = "00000000-0000-0000-0000-000000000000";

    public TaskAuthorizationResource {
        if (!RESOURCE_TYPE.equals(resourceType)) {
            throw new IllegalArgumentException("resourceType must be task");
        }
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes must not be null"));
        if (resourceId == null) {
            if (!attributes.isEmpty()) {
                throw new IllegalArgumentException("collection resources must not contain attributes");
            }
        } else {
            if (resourceId.isBlank()) {
                throw new IllegalArgumentException("resourceId must not be blank when present");
            }
            if (attributes.get(OWNER_ACCOUNT_ID) == null || attributes.get(OWNER_ACCOUNT_ID).isBlank()) {
                throw new IllegalArgumentException("ownerAccountId must not be blank");
            }
            if (!attributes.keySet().equals(Set.of(OWNER_ACCOUNT_ID))
                    && !attributes.keySet().equals(Set.of(OWNER_ACCOUNT_ID, RESOURCE_EXISTS))) {
                throw new IllegalArgumentException("task resources may contain only ownerAccountId and resourceExists");
            }
            if (attributes.containsKey(RESOURCE_EXISTS)
                    && !("true".equals(attributes.get(RESOURCE_EXISTS))
                    || "false".equals(attributes.get(RESOURCE_EXISTS)))) {
                throw new IllegalArgumentException("resourceExists must be true or false when present");
            }
        }
    }

    public static TaskAuthorizationResource forNewTask(UUID taskId, UUID ownerAccountId, String tenantId) {
        return new TaskAuthorizationResource(
                RESOURCE_TYPE,
                Objects.requireNonNull(taskId, "taskId must not be null").toString(),
                tenantId,
                Map.of(OWNER_ACCOUNT_ID, Objects.requireNonNull(ownerAccountId, "ownerAccountId must not be null")
                        .toString()));
    }

    public static TaskAuthorizationResource fromTask(Task task) {
        return forExistingTask(task.getId(), task.getOwnerAccountId(), task.getTenantId());
    }

    public static TaskAuthorizationResource forExistingTask(UUID taskId, UUID ownerAccountId, String tenantId) {
        return new TaskAuthorizationResource(
                RESOURCE_TYPE,
                Objects.requireNonNull(taskId, "taskId must not be null").toString(),
                tenantId,
                Map.of(
                        OWNER_ACCOUNT_ID,
                        Objects.requireNonNull(ownerAccountId, "ownerAccountId must not be null").toString(),
                        RESOURCE_EXISTS,
                        "true"));
    }

    public static TaskAuthorizationResource forCollection(String tenantId) {
        return new TaskAuthorizationResource(RESOURCE_TYPE, null, tenantId, Map.of());
    }

    public static TaskAuthorizationResource forMissingTask(UUID taskId, String tenantId) {
        return new TaskAuthorizationResource(
                RESOURCE_TYPE,
                Objects.requireNonNull(taskId, "taskId must not be null").toString(),
                tenantId,
                Map.of(OWNER_ACCOUNT_ID, MISSING_OWNER_ACCOUNT_ID, RESOURCE_EXISTS, "false"));
    }
}
