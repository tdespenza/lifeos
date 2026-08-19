package com.lifeos.taskgoal.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

import com.lifeos.taskgoal.authorization.TaskAccessService;
import com.lifeos.taskgoal.config.TaskGoalCertificateProjectionProperties;
import com.lifeos.taskgoal.goal.Goal;
import com.lifeos.taskgoal.goal.GoalRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class TaskGoalCertificateProjectionControllerTest {

    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final UUID SESSION_ID = UUID.randomUUID();
    private static final UUID GOAL_ID = UUID.randomUUID();
    private static final String PROOF = "a".repeat(64);
    private static final Instant COMPLETED_AT = Instant.parse("2026-08-18T12:00:00Z");

    @Mock
    private GoalRepository goalRepository;

    @Mock
    private TaskAccessService accessService;

    private TaskGoalCertificateProjectionController controller;

    @BeforeEach
    void setUp() {
        TaskGoalCertificateProjectionProperties properties = new TaskGoalCertificateProjectionProperties();
        properties.setWorkloadIdentity("trust-ledger-service");
        properties.setWorkloadToken("trust-secret");
        controller = new TaskGoalCertificateProjectionController(goalRepository, accessService, properties);
    }

    @Test
    void returnsOnlyImmutableCompletionFactsAfterOwnerAuthorization() {
        Goal goal = new Goal(GOAL_ID, "Private title", ACCOUNT_ID, ACCOUNT_ID.toString());
        goal.complete();
        when(goalRepository.findById(GOAL_ID)).thenReturn(Optional.of(goal));
        doNothing().when(accessService).authorize(any(), eq("goal:read"), any());

        var response = controller.project(
                GOAL_ID,
                "trust-ledger-service",
                "trust-secret",
                new TaskGoalCertificateProjectionController.GoalCertificateProjectionRequest(
                        ACCOUNT_ID, SESSION_ID, "PASSWORD", PROOF));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().goalId()).isEqualTo(GOAL_ID);
        assertThat(response.getBody().goalVersion()).isEqualTo(0);
        assertThat(response.getBody().completedAt()).isEqualTo(goal.getCompletedAt());
    }

    @Test
    void deniesIncompleteOrWrongOwnerWithoutDisclosingGoalExistence() {
        Goal goal = new Goal(GOAL_ID, "Private title", UUID.randomUUID(), ACCOUNT_ID.toString());
        when(goalRepository.findById(GOAL_ID)).thenReturn(Optional.of(goal));
        doNothing().when(accessService).authorize(any(), eq("goal:read"), any());

        var response = controller.project(
                GOAL_ID,
                "trust-ledger-service",
                "trust-secret",
                new TaskGoalCertificateProjectionController.GoalCertificateProjectionRequest(
                        ACCOUNT_ID, SESSION_ID, "PASSWORD", PROOF));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNull();
    }

    @Test
    void failsClosedWhenTrustWorkloadCredentialIsMissing() {
        var response = controller.project(
                GOAL_ID,
                "trust-ledger-service",
                "",
                new TaskGoalCertificateProjectionController.GoalCertificateProjectionRequest(
                        ACCOUNT_ID, SESSION_ID, "PASSWORD", PROOF));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
