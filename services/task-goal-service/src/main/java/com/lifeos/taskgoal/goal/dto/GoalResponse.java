package com.lifeos.taskgoal.goal.dto;

import com.lifeos.taskgoal.goal.Goal;
import com.lifeos.taskgoal.goal.GoalLifecycleResult;
import com.lifeos.taskgoal.goal.GoalStatus;
import java.time.Instant;
import java.util.UUID;

/** Versioned public representation of one goal lifecycle record. */
public record GoalResponse(
        UUID id,
        String title,
        GoalStatus status,
        long version,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt,
        Instant archivedAt,
        int priority,
        Instant dueAt) {

    public GoalResponse(
            UUID id,
            String title,
            GoalStatus status,
            long version,
            Instant createdAt,
            Instant updatedAt,
            Instant completedAt,
            Instant archivedAt) {
        this(id, title, status, version, createdAt, updatedAt, completedAt, archivedAt, 3, null);
    }

    public static GoalResponse from(Goal goal) {
        return new GoalResponse(
                goal.getId(),
                goal.getTitle(),
                goal.getStatus(),
                goal.getVersion(),
                goal.getCreatedAt(),
                goal.getUpdatedAt(),
                goal.getCompletedAt(),
                goal.getArchivedAt(),
                goal.getPriority(),
                goal.getDueAt());
    }

    public static GoalResponse from(GoalLifecycleResult result) {
        return new GoalResponse(
                result.id(),
                result.title(),
                result.status(),
                result.version(),
                result.createdAt(),
                result.updatedAt(),
                result.completedAt(),
                result.archivedAt(),
                result.priority(),
                result.dueAt());
    }
}
