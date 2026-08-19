package com.lifeos.assistant.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lifeos.assistant.audit.AssistantAuditService;
import com.lifeos.assistant.authorization.AssistantSubject;
import com.lifeos.assistant.journal.AssistantJournalClient;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AssistantAnalyticsRecommendationServiceTest {
    private static final AssistantSubject SUBJECT = new AssistantSubject(
            UUID.randomUUID(), UUID.randomUUID(), "password", "a".repeat(64));

    @Mock private AssistantAnalyticsClient client;
    @Mock private AssistantJournalClient profileClient;
    @Mock private AssistantAuditService auditService;

    @Test
    void mapsBoundedInsightsToNonMutatingRecommendations() {
        when(client.insights(SUBJECT, 30)).thenReturn(new AssistantAnalyticsClient.AnalyticsSnapshot(
                List.of(new AssistantAnalyticsClient.Insight(
                        "focus-time", 42, List.of("focus.minutes"), "analytics-v1")), false, List.of()));

        AssistantAnalyticsRecommendationService.AnalyticsRecommendations result =
                new AssistantAnalyticsRecommendationService(client, auditService).recommend(SUBJECT, null);

        assertThat(result.recommendations()).singleElement().satisfies(recommendation -> {
            assertThat(recommendation.key()).isEqualTo("focus-time");
            assertThat(recommendation.score()).isEqualTo(42);
            assertThat(recommendation.periodDays()).isEqualTo(30);
        });
        verify(auditService).record(any());
    }

    @Test
    void requiresExplicitAnalyticsConsentBeforeReadingInsights() {
        when(profileClient.personalization(SUBJECT)).thenReturn(
                new AssistantJournalClient.PersonalizationSnapshot(true, true, List.of("JOURNALS")));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        new AssistantAnalyticsRecommendationService(client, profileClient, auditService)
                                .recommend(SUBJECT, 30))
                .isInstanceOf(AssistantAnalyticsDeniedException.class);
        org.mockito.Mockito.verifyNoInteractions(client);
    }
}
