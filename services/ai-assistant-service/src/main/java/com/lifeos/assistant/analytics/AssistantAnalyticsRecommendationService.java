package com.lifeos.assistant.analytics;

import com.lifeos.assistant.audit.AssistantAuditOutcome;
import com.lifeos.assistant.audit.AssistantAuditRecord;
import com.lifeos.assistant.audit.AssistantAuditRequestKind;
import com.lifeos.assistant.audit.AssistantAuditService;
import com.lifeos.assistant.authorization.AssistantSubject;
import com.lifeos.assistant.journal.AssistantJournalClient;
import com.lifeos.assistant.observability.RequestContext;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Maps bounded Analytics insights into deterministic, non-mutating assistant recommendations. */
@Service
public class AssistantAnalyticsRecommendationService {
    private final AssistantAnalyticsClient client;
    private final AssistantJournalClient profileClient;
    private final AssistantAuditService auditService;

    @Autowired
    public AssistantAnalyticsRecommendationService(
            AssistantAnalyticsClient client,
            AssistantJournalClient profileClient,
            AssistantAuditService auditService) {
        this.client = client;
        this.profileClient = profileClient;
        this.auditService = auditService;
    }

    AssistantAnalyticsRecommendationService(AssistantAnalyticsClient client, AssistantAuditService auditService) {
        this(client, null, auditService);
    }

    public AnalyticsRecommendations recommend(AssistantSubject subject, Integer requestedPeriodDays) {
        int periodDays = requestedPeriodDays == null ? 30 : requestedPeriodDays;
        if (periodDays < 1 || periodDays > 90) throw new AssistantAnalyticsUnavailableException();
        try {
            if (profileClient != null) {
                AssistantJournalClient.PersonalizationSnapshot consent = profileClient.personalization(subject);
                if (!consent.consentGranted()
                        || !consent.personalizationEnabled()
                        || !consent.allowedContextCategories().contains("ANALYTICS")) {
                    throw new AssistantAnalyticsDeniedException();
                }
            }
            AssistantAnalyticsClient.AnalyticsSnapshot snapshot = client.insights(subject, periodDays);
            List<Recommendation> recommendations = snapshot.insights().stream()
                    .map(i -> new Recommendation(i.key(), message(i), i.score(), i.evidenceKeys(), periodDays))
                    .toList();
            auditService.record(new AssistantAuditRecord(
                    null, subject.accountId(), AssistantAuditRequestKind.GENERATION_REQUEST, AssistantAuditOutcome.ALLOWED,
                    "assistant-analytics-recommendations-v1", "analytics-recommendations", 0, 0, 0, "NONE", "NONE",
                    "deterministic-analytics-insights", "bounded-analytics-v1", "ANALYTICS_RECOMMENDATIONS", null,
                    recommendations.size(), null, "NONE", "NOT_REQUESTED", 0,
                    RequestContext.CORRELATION_ID.isBound() ? RequestContext.CORRELATION_ID.get() : "unbound"));
            return new AnalyticsRecommendations(recommendations, snapshot.truncated(), snapshot.limitations());
        } catch (AssistantAnalyticsDeniedException exception) { throw exception;
        } catch (AssistantAnalyticsUnavailableException exception) { throw exception; }
    }

    private static String message(AssistantAnalyticsClient.Insight insight) {
        return switch (insight.key()) {
            case "task-follow-through" -> "Keep your current task completion rhythm.";
            case "task-completion-opportunity" -> "Review open tasks and choose one small next action.";
            case "focus-time" -> "Protect another focused block based on your recent focus time.";
            default -> "Review this productivity signal before making a change.";
        };
    }

    public record AnalyticsRecommendations(List<Recommendation> recommendations, boolean truncated, List<String> limitations) {}
    public record Recommendation(String key, String message, int score, List<String> evidenceKeys, int periodDays) {
        public Recommendation(String key, String message, int score, List<String> evidenceKeys) {
            this(key, message, score, evidenceKeys, 30);
        }
    }
}
