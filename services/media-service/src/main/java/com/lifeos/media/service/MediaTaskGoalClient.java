package com.lifeos.media.service;

import com.lifeos.media.authorization.MediaSubject;
import java.time.Instant;
import java.util.UUID;

/** Narrow, workload-authenticated command port for explicit Media action-item confirmation. */
public interface MediaTaskGoalClient {

    TaskCreationResult createTask(
            MediaSubject subject, String title, Integer priority, Instant dueAt, String idempotencyKey);

    record TaskCreationResult(
            UUID id, String title, String status, long version, Instant createdAt, Instant updatedAt,
            Instant completedAt, Instant canceledAt, int priority, Instant dueAt) { }
}
