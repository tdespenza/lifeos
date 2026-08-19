package com.lifeos.media.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifeos.media.api.MediaSessionArtifactResponse;
import com.lifeos.media.api.PostSessionArtifactRequest;
import com.lifeos.media.audit.MediaSecurityAuditService;
import com.lifeos.media.authorization.MediaAccessService;
import com.lifeos.media.authorization.MediaAuthorizationActions;
import com.lifeos.media.authorization.MediaAuthorizationDenied;
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
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bounded local post-session processing. It turns an explicitly supplied transcript into a
 * deterministic summary and action-item projection, preserving a durable idempotent snapshot.
 * A provider/recording worker can replace this boundary without changing the public contract.
 */
@Service
public class MediaPostSessionService {

    private static final int MAX_SUMMARY_CHARACTERS = 4_096;
    private static final int MAX_ACTION_ITEMS = 16;
    private static final Pattern ACTION_LINE = Pattern.compile(
            "(?i)^(?:action(?: item)?|todo|follow[- ]?up)\\s*[:\\-]\\s*(.+)$");

    private final MediaAccessService accessService;
    private final MediaSessionRepository sessionRepository;
    private final MediaSessionArtifactRepository artifactRepository;
    private final MediaMutationIdempotencyService idempotencyService;
    private final MediaSecurityAuditService auditService;
    private final MediaMetrics metrics;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public MediaPostSessionService(
            MediaAccessService accessService,
            MediaSessionRepository sessionRepository,
            MediaSessionArtifactRepository artifactRepository,
            MediaMutationIdempotencyService idempotencyService,
            MediaSecurityAuditService auditService,
            MediaMetrics metrics,
            ObjectMapper objectMapper,
            Clock clock) {
        this.accessService = accessService;
        this.sessionRepository = sessionRepository;
        this.artifactRepository = artifactRepository;
        this.idempotencyService = idempotencyService;
        this.auditService = auditService;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public MediaIdempotencyResult<MediaSessionArtifactResponse> process(
            MediaSubject subject, UUID sessionId, PostSessionArtifactRequest request, String idempotencyKey) {
        Objects.requireNonNull(subject, "subject must not be null");
        Objects.requireNonNull(request, "request must not be null");
        MediaSession session = requireOwnedSession(subject, sessionId);
        ensureEnded(session);
        authorize(subject, session, MediaAuthorizationActions.SESSION_UPDATE);
        String transcript = normalizeTranscript(request.transcript());
        List<String> actionItems = extractActionItems(transcript);
        String summary = deterministicSummary(transcript);
        String actionItemsJson = serialize(actionItems);
        PostSessionCommand command = new PostSessionCommand(sessionId, transcript, summary, actionItemsJson);
        MediaIdempotencyResult<MediaSessionArtifactResponse> result = idempotencyService.execute(
                subject.accountId(),
                subject.tenantId(),
                MediaMutationOperation.SESSION_POST_PROCESS,
                sessionId.toString(),
                idempotencyKey,
                command,
                null,
                MediaSessionArtifactResponse.class,
                201,
                "/api/v1/media/sessions/" + sessionId + "/post-session",
                () -> complete(subject, session, transcript, summary, actionItemsJson, actionItems));
        auditService.record(
                subject,
                "media.session.post_process",
                result.replayed() ? MediaAuditOutcome.REPLAYED : MediaAuditOutcome.SUCCESS,
                "media-session",
                sessionId,
                null);
        metrics.record("session_post_process", result.replayed() ? "replayed" : "success");
        return result;
    }

    @Transactional(readOnly = true)
    public MediaSessionArtifactResponse get(MediaSubject subject, UUID sessionId) {
        MediaSession session = requireOwnedSession(subject, sessionId);
        authorize(subject, session, MediaAuthorizationActions.SESSION_READ);
        MediaSessionArtifact artifact = artifactRepository
                .findBySessionIdAndOwnerAccountIdAndTenantId(sessionId, subject.accountId(), subject.tenantId())
                .orElseThrow(MediaResourceNotFoundException::new);
        MediaSessionArtifactResponse result = toResponse(artifact);
        auditService.record(subject, "media.session.post_process.read", MediaAuditOutcome.SUCCESS, "media-session", sessionId, null);
        metrics.record("session_post_process_read", "success");
        return result;
    }

    private MediaSessionArtifactResponse complete(
            MediaSubject subject,
            MediaSession session,
            String transcript,
            String summary,
            String actionItemsJson,
            List<String> actionItems) {
        MediaSessionArtifact existing = artifactRepository
                .findForUpdate(session.getId(), subject.accountId(), subject.tenantId())
                .orElse(null);
        if (existing != null) {
            if (existing.getTranscript().equals(transcript)
                    && existing.getSummary().equals(summary)
                    && existing.getActionItemsJson().equals(actionItemsJson)) {
                return toResponse(existing);
            }
            throw new IllegalArgumentException("a session already has a different post-session artifact");
        }
        MediaSessionArtifact artifact = MediaSessionArtifact.ready(
                UUID.randomUUID(),
                session.getId(),
                subject.accountId(),
                subject.tenantId(),
                "LOCAL_DETERMINISTIC_TEXT",
                transcript,
                summary,
                actionItemsJson,
                clock.instant());
        artifactRepository.saveAndFlush(artifact);
        return MediaSessionArtifactResponse.from(artifact, actionItems);
    }

    private MediaSession requireOwnedSession(MediaSubject subject, UUID sessionId) {
        MediaSession session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null || !subject.accountId().equals(session.getOwnerAccountId())
                || !subject.tenantId().equals(session.getTenantId())) {
            throw new MediaResourceNotFoundException();
        }
        return session;
    }

    private void authorize(
            MediaSubject subject,
            MediaSession session,
            String action) {
        try {
            accessService.authorize(
                    subject,
                    action,
                    MediaAuthorizationResource.existingSession(session.getId(), session.getOwnerAccountId(), subject));
        } catch (MediaAuthorizationDenied exception) {
            auditService.record(subject, "media.session.post_process", MediaAuditOutcome.DENIED,
                    "media-session", session.getId(), "IDENTITY_DENIED");
            throw exception;
        }
    }

    private static void ensureEnded(MediaSession session) {
        if (session.getStatus() != com.lifeos.media.domain.MediaSessionStatus.ENDED) {
            throw new com.lifeos.media.domain.MediaLifecycleTransitionException("post-process");
        }
    }

    private static String normalizeTranscript(String transcript) {
        String normalized = transcript == null ? "" : transcript.replace("\u0000", " ").replaceAll("\\s+", " ").strip();
        if (normalized.isBlank() || normalized.length() > 65_536) {
            throw new IllegalArgumentException("transcript must be nonblank and bounded");
        }
        return normalized;
    }

    private static String deterministicSummary(String transcript) {
        String[] sentences = transcript.split("(?<=[.!?])\\s+");
        StringBuilder summary = new StringBuilder("Local deterministic session summary: ");
        for (String sentence : sentences) {
            String candidate = sentence.strip();
            if (candidate.isBlank()) continue;
            if (summary.length() > "Local deterministic session summary: ".length()) summary.append(' ');
            if (summary.length() + candidate.length() > MAX_SUMMARY_CHARACTERS) break;
            summary.append(candidate);
            if (summary.length() >= MAX_SUMMARY_CHARACTERS) break;
        }
        return summary.toString();
    }

    private static List<String> extractActionItems(String transcript) {
        List<String> actionItems = new ArrayList<>();
        for (String line : transcript.split("(?<=[.!?])\\s+|\\n")) {
            String candidate = line.strip();
            java.util.regex.Matcher matcher = ACTION_LINE.matcher(candidate);
            if (matcher.matches() && !matcher.group(1).isBlank()) {
                actionItems.add(matcher.group(1).strip());
            }
            if (actionItems.size() == MAX_ACTION_ITEMS) break;
        }
        return List.copyOf(actionItems);
    }

    private String serialize(List<String> actionItems) {
        try {
            return objectMapper.writeValueAsString(actionItems);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("action items could not be serialized", exception);
        }
    }

    private MediaSessionArtifactResponse toResponse(MediaSessionArtifact artifact) {
        try {
            List<String> actionItems = objectMapper.readValue(
                    artifact.getActionItemsJson(), objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
            return MediaSessionArtifactResponse.from(artifact, actionItems);
        } catch (JsonProcessingException exception) {
            throw new MediaResourceNotFoundException();
        }
    }

    private record PostSessionCommand(UUID sessionId, String transcript, String summary, String actionItemsJson) {
    }
}
