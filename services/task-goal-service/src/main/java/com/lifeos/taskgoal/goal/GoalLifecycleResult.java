package com.lifeos.taskgoal.goal;

import java.time.Instant;
import java.util.UUID;

/** Immutable snapshot returned by a completed lifecycle mutation and its idempotent replays. */
public record GoalLifecycleResult(
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

    public GoalLifecycleResult(
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

    /** Captures an already-flushed goal so its optimistic version is included. */
    public static GoalLifecycleResult from(Goal goal) {
        return new GoalLifecycleResult(
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
}
