package com.lifeos.assistant.tool;

import com.lifeos.assistant.authorization.AssistantSubject;
import java.time.Instant;
import java.util.UUID;

/** Bounded adapter for the one currently executable assistant task tool. */
public interface AssistantTaskGoalClient {

    TaskCreationResult createTask(
            AssistantSubject subject,
            String title,
            Integer priority,
            Instant dueAt,
            String idempotencyKey);

    TaskCreationResult createGoal(
            AssistantSubject subject,
            String title,
            Integer priority,
            Instant dueAt,
            String idempotencyKey);

    PlanningSnapshot planningSnapshot(AssistantSubject subject, int maxResults);

    record TaskCreationResult(
            UUID id,
            String title,
            String status,
            long version,
            Instant createdAt,
            Instant updatedAt,
            Instant completedAt,
            Instant canceledAt,
            int priority,
            Instant dueAt) {
    }

    record PlanningSnapshot(java.util.List<PlanningFact> facts) {
    }

    record PlanningFact(
            String resourceType,
            UUID resourceId,
            String title,
            String status,
            int priority,
            Instant dueAt) {
    }
}
