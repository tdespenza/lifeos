package com.lifeos.taskgoal.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

import com.lifeos.taskgoal.authorization.GoalAuthorizationResource;
import com.lifeos.taskgoal.authorization.TaskAccessService;
import com.lifeos.taskgoal.authorization.TaskAuthorizationResource;
import com.lifeos.taskgoal.config.TaskGoalAssistantToolProperties;
import com.lifeos.taskgoal.goal.Goal;
import com.lifeos.taskgoal.goal.GoalRepository;
import com.lifeos.taskgoal.task.Task;
import com.lifeos.taskgoal.task.TaskRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AssistantPlanningSnapshotControllerTest {

    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final UUID SESSION_ID = UUID.randomUUID();
    private static final String PROOF = "a".repeat(64);

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private GoalRepository goalRepository;

    @Mock
    private TaskAccessService accessService;

    private AssistantPlanningSnapshotController controller;

    @BeforeEach
    void setUp() {
        TaskGoalAssistantToolProperties properties = new TaskGoalAssistantToolProperties();
        properties.setWorkloadIdentity("ai-assistant-service");
        properties.setWorkloadToken("assistant-secret");
        controller = new AssistantPlanningSnapshotController(taskRepository, goalRepository, accessService, properties);
        lenient().when(taskRepository.findAllByOwnerAccountIdAndTenantIdOrderByCreatedAtAscIdAsc(ACCOUNT_ID, ACCOUNT_ID.toString()))
                .thenReturn(List.of(
                        new Task(
                                UUID.randomUUID(),
                                "Plan release",
                                ACCOUNT_ID,
                                ACCOUNT_ID.toString(),
                                1,
                                Instant.parse("2026-08-19T09:00:00Z"))));
        lenient().when(goalRepository.findAllByOwnerAccountIdAndTenantIdOrderByCreatedAtAscIdAsc(ACCOUNT_ID, ACCOUNT_ID.toString()))
                .thenReturn(List.of(
                        new Goal(
                                UUID.randomUUID(),
                                "Ship product",
                                ACCOUNT_ID,
                                ACCOUNT_ID.toString(),
                                0,
                                Instant.parse("2026-08-20T09:00:00Z"))));
    }

    @Test
    void returnsOnlyActiveOwnerScopedFactsAfterBothCollectionDecisions() {
        var response = controller.snapshot(
                "ai-assistant-service",
                "assistant-secret",
                new AssistantPlanningSnapshotController.PlanningSnapshotRequest(
                        ACCOUNT_ID, SESSION_ID, "password", PROOF, 8));

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().facts()).hasSize(2);
        assertThat(response.getBody().facts()).extracting("resourceType")
                .containsExactly("TASK", "GOAL");
        verify(accessService).authorize(any(), eq("task:list"), any(TaskAuthorizationResource.class));
        verify(accessService).authorize(any(), eq("goal:list"), any(GoalAuthorizationResource.class));
    }

    @Test
    void rejectsAnUnconfiguredOrMismatchedWorkloadBeforeReadingPlanningData() {
        assertThatThrownBy(() -> controller.snapshot(
                "wrong-service",
                "assistant-secret",
                new AssistantPlanningSnapshotController.PlanningSnapshotRequest(
                        ACCOUNT_ID, SESSION_ID, "password", PROOF, 8)))
                .isInstanceOf(AssistantPlanningSnapshotController.AssistantPlanningWorkloadUnauthorizedException.class);
    }

    @Test
    void appliesTheCallerRequestedBound() {
        var response = controller.snapshot(
                "ai-assistant-service",
                "assistant-secret",
                new AssistantPlanningSnapshotController.PlanningSnapshotRequest(
                        ACCOUNT_ID, SESSION_ID, "password", PROOF, 1));

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().facts()).hasSize(1);
    }
}
