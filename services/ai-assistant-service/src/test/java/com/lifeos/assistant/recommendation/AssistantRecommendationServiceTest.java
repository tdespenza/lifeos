package com.lifeos.assistant.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lifeos.assistant.audit.AssistantAuditService;
import com.lifeos.assistant.authorization.AssistantSubject;
import com.lifeos.assistant.tool.AssistantTaskGoalClient;
import com.lifeos.assistant.tool.AssistantTaskToolUnavailableException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AssistantRecommendationServiceTest {

    private static final AssistantSubject SUBJECT = new AssistantSubject(
            UUID.randomUUID(), UUID.randomUUID(), "password", "a".repeat(64));

    @Mock
    private AssistantTaskGoalClient taskGoalClient;

    @Mock
    private AssistantAuditService auditService;

    @Test
    void ranksOverdueAndUrgentOwnerFactsDeterministically() {
        Instant now = Instant.now();
        when(taskGoalClient.planningSnapshot(SUBJECT, 8)).thenReturn(new AssistantTaskGoalClient.PlanningSnapshot(List.of(
                new AssistantTaskGoalClient.PlanningFact(
                        "TASK", UUID.randomUUID(), "Future low priority", "ACTIVE", 4, now.plusSeconds(86_400)),
                new AssistantTaskGoalClient.PlanningFact(
                        "GOAL", UUID.randomUUID(), "Overdue goal", "ACTIVE", 3, now.minusSeconds(60)),
                new AssistantTaskGoalClient.PlanningFact(
                        "TASK", UUID.randomUUID(), "Critical task", "ACTIVE", 0, now.plusSeconds(172_800)))));

        List<AssistantRecommendationService.Recommendation> result = new AssistantRecommendationService(
                taskGoalClient, auditService).recommend(SUBJECT, 3);

        assertThat(result).extracting(AssistantRecommendationService.Recommendation::title)
                .containsExactly("Overdue goal", "Critical task", "Future low priority");
        assertThat(result.getFirst().reason()).isEqualTo("Overdue active goal");
        verify(auditService).record(any());
    }

    @Test
    void failsClosedWhenThePlanningProjectionIsUnavailable() {
        when(taskGoalClient.planningSnapshot(SUBJECT, 8))
                .thenThrow(new AssistantTaskToolUnavailableException());

        assertThatThrownBy(() -> new AssistantRecommendationService(taskGoalClient, auditService)
                .recommend(SUBJECT, 5))
                .isInstanceOf(AssistantRecommendationUnavailableException.class);
        verify(auditService).record(any());
    }
}
