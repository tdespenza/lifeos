package com.lifeos.taskgoal.task.dto;

import com.lifeos.taskgoal.task.Task;
import com.lifeos.taskgoal.task.TaskLifecycleResult;
import com.lifeos.taskgoal.task.TaskStatus;
import java.time.Instant;
import java.util.UUID;

/** Versioned public Task representation. */
public record TaskResponse(
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

    public TaskResponse(
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

    public static TaskResponse from(Task task) {
        return new TaskResponse(
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

    public static TaskResponse from(TaskLifecycleResult result) {
        return new TaskResponse(
                result.id(),
                result.title(),
                result.status(),
                result.version(),
                result.createdAt(),
                result.updatedAt(),
                result.completedAt(),
                result.canceledAt(),
                result.priority(),
                result.dueAt());
    }
}
