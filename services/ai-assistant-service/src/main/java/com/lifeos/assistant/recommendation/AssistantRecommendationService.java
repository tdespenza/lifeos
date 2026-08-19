package com.lifeos.assistant.recommendation;

import com.lifeos.assistant.audit.AssistantAuditOutcome;
import com.lifeos.assistant.audit.AssistantAuditRecord;
import com.lifeos.assistant.audit.AssistantAuditRequestKind;
import com.lifeos.assistant.audit.AssistantAuditService;
import com.lifeos.assistant.authorization.AssistantSubject;
import com.lifeos.assistant.observability.RequestContext;
import com.lifeos.assistant.tool.AssistantTaskGoalClient;
import com.lifeos.assistant.tool.AssistantTaskToolDeniedException;
import com.lifeos.assistant.tool.AssistantTaskToolUnavailableException;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Deterministic, owner-scoped goal/task recommendation ranking. */
@Service
public class AssistantRecommendationService {

    public static final int DEFAULT_MAX_RESULTS = 5;
    public static final int MAX_RESULTS = 8;

    private final AssistantTaskGoalClient taskGoalClient;
    private final AssistantAuditService auditService;

    public AssistantRecommendationService(
            AssistantTaskGoalClient taskGoalClient, AssistantAuditService auditService) {
        this.taskGoalClient = taskGoalClient;
        this.auditService = auditService;
    }

    public List<Recommendation> recommend(AssistantSubject subject, Integer requestedMaxResults) {
        int maxResults = requestedMaxResults == null ? DEFAULT_MAX_RESULTS : requestedMaxResults;
        if (maxResults < 1 || maxResults > MAX_RESULTS) {
            throw new AssistantRecommendationUnavailableException();
        }
        long started = System.nanoTime();
        AssistantTaskGoalClient.PlanningSnapshot snapshot;
        try {
            snapshot = taskGoalClient.planningSnapshot(subject, Math.max(maxResults, MAX_RESULTS));
        } catch (AssistantTaskToolDeniedException exception) {
            audit(subject, AssistantAuditOutcome.TOOL_REJECTED, "PLANNING_DENIED", List.of(), started, 0);
            throw new AssistantRecommendationDeniedException();
        } catch (AssistantTaskToolUnavailableException exception) {
            audit(subject, AssistantAuditOutcome.PROVIDER_FAILED, "PLANNING_UNAVAILABLE", List.of(), started, 0);
            throw new AssistantRecommendationUnavailableException(exception);
        }
        if (snapshot == null || snapshot.facts() == null) {
            audit(subject, AssistantAuditOutcome.PROVIDER_FAILED, "PLANNING_UNAVAILABLE", List.of(), started, 0);
            throw new AssistantRecommendationUnavailableException();
        }
        Instant now = Instant.now();
        List<Recommendation> recommendations = snapshot.facts().stream()
                .filter(fact -> fact != null && fact.resourceId() != null && fact.title() != null)
                .sorted(priorityOrder(now))
                .limit(maxResults)
                .map(fact -> toRecommendation(fact, now))
                .toList();
        audit(
                subject,
                AssistantAuditOutcome.ALLOWED,
                recommendations.isEmpty() ? "NO_ACTIONABLE_ITEMS" : "GOAL_RECOMMENDATIONS",
                recommendations.stream().map(Recommendation::resourceId).toList(),
                started,
                recommendations.stream().mapToInt(recommendation -> recommendation.title().length()).sum());
        return recommendations;
    }

    private static Comparator<AssistantTaskGoalClient.PlanningFact> priorityOrder(Instant now) {
        return Comparator
                .comparing((AssistantTaskGoalClient.PlanningFact fact) -> isOverdue(fact.dueAt(), now))
                .reversed()
                .thenComparingInt(AssistantTaskGoalClient.PlanningFact::priority)
                .thenComparing(AssistantTaskGoalClient.PlanningFact::dueAt,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(AssistantTaskGoalClient.PlanningFact::resourceId);
    }

    private static boolean isOverdue(Instant dueAt, Instant now) {
        return dueAt != null && dueAt.isBefore(now);
    }

    private static Recommendation toRecommendation(
            AssistantTaskGoalClient.PlanningFact fact, Instant now) {
        String reason;
        if (isOverdue(fact.dueAt(), now)) {
            reason = "Overdue active " + fact.resourceType().toLowerCase();
        } else if (fact.dueAt() != null && !fact.dueAt().isAfter(now.plus(Duration.ofDays(2)))) {
            reason = "Due within two days";
        } else if (fact.priority() <= 1) {
            reason = "High priority active work";
        } else {
            reason = "Next active planning item";
        }
        return new Recommendation(
                fact.resourceType(), fact.resourceId(), fact.title(), reason, fact.priority(), fact.dueAt());
    }

    private void audit(
            AssistantSubject subject,
            AssistantAuditOutcome outcome,
            String summary,
            List<UUID> sourceIds,
            long started,
            int outputCharacters) {
        String correlationId = RequestContext.CORRELATION_ID.isBound()
                ? RequestContext.CORRELATION_ID.get()
                : "unbound";
        String contextIds = sourceIds.isEmpty()
                ? "NONE"
                : sourceIds.stream().limit(12).map(UUID::toString).reduce((left, right) -> left + "," + right).orElse("NONE");
        auditService.record(new AssistantAuditRecord(
                null,
                subject.accountId(),
                AssistantAuditRequestKind.GENERATION_REQUEST,
                outcome,
                "assistant-goal-planning-recommendations-v1",
                "goal-planning",
                13,
                7,
                0,
                contextIds,
                "NONE",
                "deterministic-planner",
                "priority-deadline-ranker-v1",
                summary,
                null,
                outputCharacters,
                null,
                "NONE",
                "NOT_REQUESTED",
                Math.max(0L, (System.nanoTime() - started) / 1_000_000L),
                correlationId));
    }

    public record Recommendation(
            String resourceType,
            UUID resourceId,
            String title,
            String reason,
            int priority,
            Instant dueAt) {
    }
}
