package com.lifeos.taskgoal.task;

import java.time.Instant;
import java.util.UUID;

/** Immutable Task response snapshot retained for exact idempotent mutation replay. */
public record TaskLifecycleResult(
        UUID id,
        String title,
        TaskStatus status,
        long version,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt,
        Instant canceledAt,
        int priority,
        Instant dueAt) {

    public TaskLifecycleResult(
            UUID id,
            String title,
            TaskStatus status,
            long version,
            Instant createdAt,
            Instant updatedAt,
            Instant completedAt,
            Instant canceledAt) {
        this(id, title, status, version, createdAt, updatedAt, completedAt, canceledAt, 3, null);
    }

    public static TaskLifecycleResult from(Task task) {
        return new TaskLifecycleResult(
                task.getId(),
                task.getTitle(),
                task.getStatus(),
                task.getVersion(),
                task.getCreatedAt(),
                task.getUpdatedAt(),
                task.getCompletedAt(),
                task.getCanceledAt(),
                task.getPriority(),
                task.getDueAt());
    }
}
