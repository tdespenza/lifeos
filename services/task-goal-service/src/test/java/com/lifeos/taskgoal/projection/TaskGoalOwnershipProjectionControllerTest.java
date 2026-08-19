package com.lifeos.taskgoal.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lifeos.taskgoal.authorization.TaskAccessService;
import com.lifeos.taskgoal.config.TaskGoalProjectionProperties;
import com.lifeos.taskgoal.goal.GoalRepository;
import com.lifeos.taskgoal.task.Task;
import com.lifeos.taskgoal.task.TaskRepository;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class TaskGoalOwnershipProjectionControllerTest {

    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final UUID SESSION_ID = UUID.randomUUID();
    private static final UUID TASK_ID = UUID.randomUUID();
    private static final String PROOF = "a".repeat(64);

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private GoalRepository goalRepository;

    @Mock
    private TaskAccessService accessService;

    private TaskGoalOwnershipProjectionController controller;

    @BeforeEach
    void setUp() {
        TaskGoalProjectionProperties properties = new TaskGoalProjectionProperties();
        properties.setWorkloadIdentity("calendar-service");
        properties.setWorkloadToken("calendar-secret");
        controller = new TaskGoalOwnershipProjectionController(taskRepository, goalRepository, accessService, properties);
    }

    @Test
    void returnsNoContentForAnOwnedTaskAfterIdentityReauthorization() {
        Task task = new Task(TASK_ID, "Focus", ACCOUNT_ID, ACCOUNT_ID.toString());
        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(task));
        doNothing().when(accessService).authorize(any(), eq("task:read"), any());

        var response = controller.verify(
                "calendar-service",
                "calendar-secret",
                new TaskGoalOwnershipProjectionController.OwnershipProjectionRequest(
                        ACCOUNT_ID, SESSION_ID, "PASSWORD", PROOF, "TASK", TASK_ID.toString()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(accessService).authorize(any(), eq("task:read"), any());
    }

    @Test
    void returnsGenericDenialForMissingOrCrossOwnerTask() {
        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.empty());

        var response = controller.verify(
                "calendar-service",
                "calendar-secret",
                new TaskGoalOwnershipProjectionController.OwnershipProjectionRequest(
                        ACCOUNT_ID, SESSION_ID, "PASSWORD", PROOF, "TASK", TASK_ID.toString()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNull();
    }

    @Test
    void failsClosedWhenTheInboundWorkloadCredentialIsMissing() {
        var response = controller.verify(
                "calendar-service",
                "",
                new TaskGoalOwnershipProjectionController.OwnershipProjectionRequest(
                        ACCOUNT_ID, SESSION_ID, "PASSWORD", PROOF, "TASK", TASK_ID.toString()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void returnsOnlyPriorityAndDeadlineForAnOwnedPlanningTask() {
        Task task = new Task(
                TASK_ID,
                "Focus",
                ACCOUNT_ID,
                ACCOUNT_ID.toString(),
                0,
                Instant.parse("2026-08-19T09:00:00Z"));
        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(task));
        doNothing().when(accessService).authorize(any(), eq("task:read"), any());

        var response = controller.project(
                "calendar-service",
                "calendar-secret",
                new TaskGoalPlanningProjectionRequest(
                        ACCOUNT_ID, SESSION_ID, "PASSWORD", PROOF, "TASK", TASK_ID.toString()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(new TaskGoalPlanningProjectionResponse(
                "TASK", TASK_ID, 0, Instant.parse("2026-08-19T09:00:00Z")));
    }
}
