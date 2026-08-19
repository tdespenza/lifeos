package com.lifeos.media.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifeos.media.api.ConfirmSessionActionRequest;
import com.lifeos.media.api.FollowUpTaskResponse;
import com.lifeos.media.audit.MediaSecurityAuditService;
import com.lifeos.media.authorization.MediaAccessService;
import com.lifeos.media.authorization.MediaAuthorizationActions;
import com.lifeos.media.authorization.MediaAuthorizationResource;
import com.lifeos.media.authorization.MediaSubject;
import com.lifeos.media.domain.MediaAuditOutcome;
import com.lifeos.media.domain.MediaSession;
import com.lifeos.media.domain.MediaSessionArtifact;
import com.lifeos.media.domain.MediaSessionArtifactRepository;
import com.lifeos.media.domain.MediaSessionRepository;
import com.lifeos.media.idempotency.MediaIdempotencyResult;
import com.lifeos.media.idempotency.MediaMutationIdempotencyService;
import com.lifeos.media.idempotency.MediaMutationOperation;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Converts one user-confirmed action item into one durable, idempotent TaskGoal command. */
@Service
public class MediaFollowUpTaskService {

    private final MediaAccessService accessService;
    private final MediaSessionRepository sessionRepository;
    private final MediaSessionArtifactRepository artifactRepository;
    private final MediaMutationIdempotencyService idempotencyService;
    private final MediaTaskGoalClient taskGoalClient;
    private final MediaSecurityAuditService auditService;
    private final MediaMetrics metrics;
    private final ObjectMapper objectMapper;

    public MediaFollowUpTaskService(
            MediaAccessService accessService,
            MediaSessionRepository sessionRepository,
            MediaSessionArtifactRepository artifactRepository,
            MediaMutationIdempotencyService idempotencyService,
            MediaTaskGoalClient taskGoalClient,
            MediaSecurityAuditService auditService,
            MediaMetrics metrics,
            ObjectMapper objectMapper) {
        this.accessService = accessService;
        this.sessionRepository = sessionRepository;
        this.artifactRepository = artifactRepository;
        this.idempotencyService = idempotencyService;
        this.taskGoalClient = taskGoalClient;
        this.auditService = auditService;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
    }

    public MediaIdempotencyResult<FollowUpTaskResponse> confirm(
            MediaSubject subject,
            UUID sessionId,
            long expectedArtifactVersion,
            ConfirmSessionActionRequest request,
            String idempotencyKey) {
        Objects.requireNonNull(subject, "subject must not be null");
        Objects.requireNonNull(request, "request must not be null");
        MediaSession session = requireOwnedSession(subject, sessionId);
        MediaSessionArtifact artifact = artifactRepository
                .findBySessionIdAndOwnerAccountIdAndTenantId(sessionId, subject.accountId(), subject.tenantId())
                .orElseThrow(MediaResourceNotFoundException::new);
        if (artifact.getVersion() != expectedArtifactVersion) {
            throw new MediaVersionConflictException();
        }
        authorize(subject, session);
        if (!actionItems(artifact).contains(request.actionItem())) {
            throw new IllegalArgumentException("action item is not present in the post-session artifact");
        }
        FollowUpCommand command = new FollowUpCommand(
                sessionId, request.actionItem(), request.priority(), request.dueAt(), expectedArtifactVersion);
        // The action item itself is the business identity. This stronger key prevents a second
        // task when a client repeats the confirmation with a fresh HTTP idempotency key.
        String actionCommandKey = "media-action-" + digest(sessionId + ":" + request.actionItem());
        MediaIdempotencyResult<FollowUpTaskResponse> result = idempotencyService.execute(
                subject.accountId(), subject.tenantId(), MediaMutationOperation.SESSION_FOLLOW_UP_TASK,
                sessionId + ":" + digest(request.actionItem()), actionCommandKey, command, expectedArtifactVersion,
                FollowUpTaskResponse.class, 201, "/api/v1/media/sessions/" + sessionId + "/post-session/tasks",
                () -> FollowUpTaskResponse.from(taskGoalClient.createTask(
                        subject, request.actionItem(), request.priority(), request.dueAt(), remoteKey(sessionId, request.actionItem()))));
        auditService.record(subject, "media.session.follow_up_task", result.replayed() ? MediaAuditOutcome.REPLAYED : MediaAuditOutcome.SUCCESS,
                "media-session", sessionId, null);
        metrics.record("session_follow_up_task", result.replayed() ? "replayed" : "success");
        return result;
    }

    private MediaSession requireOwnedSession(MediaSubject subject, UUID sessionId) {
        MediaSession session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null || !subject.accountId().equals(session.getOwnerAccountId())
                || !subject.tenantId().equals(session.getTenantId())) {
            throw new MediaResourceNotFoundException();
        }
        return session;
    }

    private void authorize(MediaSubject subject, MediaSession session) {
        accessService.authorize(subject, MediaAuthorizationActions.SESSION_UPDATE,
                MediaAuthorizationResource.existingSession(session.getId(), session.getOwnerAccountId(), subject));
    }

    private List<String> actionItems(MediaSessionArtifact artifact) {
        try {
            List<String> items = objectMapper.readValue(artifact.getActionItemsJson(),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
            if (items.size() > 16) throw new MediaResourceNotFoundException();
            return List.copyOf(items);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new MediaResourceNotFoundException();
        }
    }

    private static String remoteKey(UUID sessionId, String actionItem) {
        return "mediafollowup-" + digest(sessionId + ":" + actionItem);
    }

    private static String digest(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte item : hash) result.append(String.format("%02x", item));
            return result.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private record FollowUpCommand(UUID sessionId, String actionItem, Integer priority, Instant dueAt, long artifactVersion) { }
}
