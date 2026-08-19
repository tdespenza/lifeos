package com.lifeos.taskgoal.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lifeos.taskgoal.config.TaskGoalMediaToolProperties;
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
class MediaTaskMutationControllerTest {

    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final UUID SESSION_ID = UUID.randomUUID();
    private static final String PROOF = "b".repeat(64);

    @Mock
    private TaskService taskService;

    private MediaTaskMutationController controller;

    @BeforeEach
    void setUp() {
        TaskGoalMediaToolProperties properties = new TaskGoalMediaToolProperties();
        properties.setWorkloadIdentity("media-service");
        properties.setWorkloadToken("media-secret");
        controller = new MediaTaskMutationController(taskService, properties);
    }

    @Test
    void rejectsMissingOrMismatchedWorkloadBeforeCallingTaskService() {
        assertThatThrownBy(() -> controller.create(
                "wrong-service", "media-secret", List.of("media-task-key"), request()))
                .isInstanceOf(MediaTaskMutationController.MediaTaskWorkloadUnauthorizedException.class);
    }

    @Test
    void forwardsIdentityProofAndIdempotencyKeyToTaskService() {
        UUID taskId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-08-18T12:00:00Z");
        when(taskService.create(any(), eq("Send plan"), eq(2), eq(createdAt), eq("media-task-key")))
                .thenReturn(new TaskLifecycleResult(
                        taskId, "Send plan", TaskStatus.ACTIVE, 0, createdAt, createdAt,
                        null, null, 2, createdAt));

        var response = controller.create("media-service", "media-secret", List.of("media-task-key"), request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().id()).isEqualTo(taskId);
        verify(taskService).create(any(), eq("Send plan"), eq(2), eq(createdAt), eq("media-task-key"));
    }

    private static MediaTaskMutationController.FollowUpTaskRequest request() {
        return new MediaTaskMutationController.FollowUpTaskRequest(
                ACCOUNT_ID, SESSION_ID, "password", PROOF, "Send plan", 2,
                Instant.parse("2026-08-18T12:00:00Z"));
    }
}
