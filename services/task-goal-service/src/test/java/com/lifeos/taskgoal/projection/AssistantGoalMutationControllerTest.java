package com.lifeos.taskgoal.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lifeos.taskgoal.config.TaskGoalAssistantToolProperties;
import com.lifeos.taskgoal.goal.Goal;
import com.lifeos.taskgoal.goal.GoalService;
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
class AssistantGoalMutationControllerTest {

    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final UUID SESSION_ID = UUID.randomUUID();
    private static final String PROOF = "a".repeat(64);

    @Mock
    private GoalService goalService;

    private AssistantGoalMutationController controller;

    @BeforeEach
    void setUp() {
        TaskGoalAssistantToolProperties properties = new TaskGoalAssistantToolProperties();
        properties.setWorkloadIdentity("ai-assistant-service");
        properties.setWorkloadToken("assistant-secret");
        controller = new AssistantGoalMutationController(goalService, properties);
    }

    @Test
    void rejectsMissingOrMismatchedWorkloadBeforeCallingGoalService() {
        assertThatThrownBy(() -> controller.create(
                        "wrong-service", "assistant-secret", List.of("goal-key"), request()))
                .isInstanceOf(AssistantGoalMutationController.AssistantGoalWorkloadUnauthorizedException.class);
    }

    @Test
    void forwardsIdentityProofAndIdempotencyKeyToGoalService() {
        UUID goalId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-08-18T12:00:00Z");
        when(goalService.create(any(), eq("Finish roadmap"), eq(1), eq(createdAt), eq("goal-key")))
                .thenReturn(new Goal(goalId, "Finish roadmap", ACCOUNT_ID, "personal:" + ACCOUNT_ID, 1, createdAt));

        var response = controller.create("ai-assistant-service", "assistant-secret", List.of("goal-key"), request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().id()).isEqualTo(goalId);
        verify(goalService).create(any(), eq("Finish roadmap"), eq(1), eq(createdAt), eq("goal-key"));
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

    private static AssistantGoalMutationController.AssistantGoalMutationRequest request() {
        return new AssistantGoalMutationController.AssistantGoalMutationRequest(
                ACCOUNT_ID,
                SESSION_ID,
                "password",
                PROOF,
                "Finish roadmap",
                1,
                Instant.parse("2026-08-18T12:00:00Z"));
    }
}
