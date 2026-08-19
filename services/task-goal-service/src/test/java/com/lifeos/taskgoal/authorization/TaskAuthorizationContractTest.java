package com.lifeos.taskgoal.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Locks the task-goal side of Identity's closed v2 Task authorization descriptor contract. */
class TaskAuthorizationContractTest {

    @Test
    void exposesOnlyTheExactV2TaskActionStrings() {
        assertThat(new String[] {
            TaskAuthorizationActions.CREATE,
            TaskAuthorizationActions.LIST,
            TaskAuthorizationActions.READ,
            TaskAuthorizationActions.UPDATE,
            TaskAuthorizationActions.COMPLETE,
            TaskAuthorizationActions.CANCEL,
            TaskAuthorizationActions.DEPENDENCY_MANAGE,
            TaskAuthorizationActions.DEPENDENCY_ORDER
        }).containsExactly(
                "task:create",
                "task:list",
                "task:read",
                "task:update",
                "task:complete",
                "task:cancel",
                "task:dependency-manage",
                "task:dependency-order");
    }

    @Test
    void derivesOwnedTaskAndDependencyCollectionFactsWithoutClientControlledAttributes() {
        UUID taskId = UUID.randomUUID();
        UUID ownerAccountId = UUID.randomUUID();
        String tenantId = ownerAccountId.toString();

        TaskAuthorizationResource newTask = TaskAuthorizationResource.forNewTask(taskId, ownerAccountId, tenantId);
        TaskAuthorizationResource missingTask = TaskAuthorizationResource.forMissingTask(taskId, tenantId);
        TaskDependencyGraphAuthorizationResource graph =
                TaskDependencyGraphAuthorizationResource.forCollection(tenantId);

        assertThat(newTask.resourceType()).isEqualTo("task");
        assertThat(newTask.resourceId()).isEqualTo(taskId.toString());
        assertThat(newTask.attributes()).isEqualTo(Map.of("ownerAccountId", ownerAccountId.toString()));
        assertThat(missingTask.attributes()).isEqualTo(Map.of(
                "ownerAccountId", "00000000-0000-0000-0000-000000000000", "resourceExists", "false"));
        assertThat(graph.resourceType()).isEqualTo("task-dependency-graph");
        assertThat(graph.resourceId()).isNull();
        assertThat(graph.tenantId()).isEqualTo(tenantId);
        assertThat(graph.attributes()).isEmpty();
    }
}
