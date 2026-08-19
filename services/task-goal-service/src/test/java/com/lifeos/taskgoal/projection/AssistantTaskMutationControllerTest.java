package com.lifeos.taskgoal.projection;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lifeos.taskgoal.config.TaskGoalAssistantToolProperties;
import com.lifeos.taskgoal.task.TaskLifecycleResult;
import com.lifeos.taskgoal.task.TaskService;
import com.lifeos.taskgoal.task.TaskStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class AssistantTaskMutationControllerTest {

    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final UUID SESSION_ID = UUID.randomUUID();
    private static final String PROOF = "a".repeat(64);

    @Mock
    private TaskService taskService;

    private AssistantTaskMutationController controller;

    @BeforeEach
    void setUp() {
        TaskGoalAssistantToolProperties properties = new TaskGoalAssistantToolProperties();
        properties.setWorkloadIdentity("ai-assistant-service");
        properties.setWorkloadToken("assistant-secret");
        controller = new AssistantTaskMutationController(taskService, properties);
    }

    @Test
    void rejectsMissingOrMismatchedWorkloadBeforeCallingTaskService() {
        var request = request();

        assertThatThrownBy(() -> controller.create(
                "wrong-service", "assistant-secret", List.of("task-key"), request))
                .isInstanceOf(AssistantTaskMutationController.AssistantTaskWorkloadUnauthorizedException.class);
    }

    @Test
    void forwardsIdentityProofAndIdempotencyKeyToTaskService() {
        UUID taskId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-08-18T12:00:00Z");
        when(taskService.create(any(), eq("Write plan"), eq(2), eq(createdAt), eq("task-key")))
                .thenReturn(new TaskLifecycleResult(
                        taskId,
                        "Write plan",
                        TaskStatus.ACTIVE,
                        0,
                        createdAt,
                        createdAt,
                        null,
                        null,
                        2,
                        createdAt));

        var response = controller.create("ai-assistant-service", "assistant-secret", List.of("task-key"), request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().id()).isEqualTo(taskId);
        verify(taskService).create(any(), eq("Write plan"), eq(2), eq(createdAt), eq("task-key"));
    }

    @Test
    void rejectsDuplicateOrMissingIdempotencyHeaders() {
        assertThatThrownBy(() -> controller.create(
                "ai-assistant-service", "assistant-secret", List.of("a", "b"), request()))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> controller.create(
                "ai-assistant-service", "assistant-secret", List.of(), request()))
                .isInstanceOf(RuntimeException.class);
    }

    private static AssistantTaskMutationController.AssistantTaskMutationRequest request() {
        return new AssistantTaskMutationController.AssistantTaskMutationRequest(
                ACCOUNT_ID,
                SESSION_ID,
                "password",
                PROOF,
                "Write plan",
                2,
                Instant.parse("2026-08-18T12:00:00Z"));
    }
}
