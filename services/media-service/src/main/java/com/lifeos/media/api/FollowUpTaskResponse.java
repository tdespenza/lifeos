package com.lifeos.media.api;

import com.lifeos.media.service.MediaTaskGoalClient;
import java.time.Instant;
import java.util.UUID;

/** Public-safe projection of the TaskGoal created after explicit action-item confirmation. */
public record FollowUpTaskResponse(
        UUID id, String title, String status, long version, Instant createdAt, Instant updatedAt,
        Instant completedAt, Instant canceledAt, int priority, Instant dueAt) {

    public static FollowUpTaskResponse from(MediaTaskGoalClient.TaskCreationResult result) {
        return new FollowUpTaskResponse(
                result.id(), result.title(), result.status(), result.version(), result.createdAt(),
                result.updatedAt(), result.completedAt(), result.canceledAt(), result.priority(), result.dueAt());
    }
}
