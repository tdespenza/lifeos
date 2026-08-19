package com.lifeos.media.service;

import com.lifeos.media.api.MediaSessionAnchorResponse;
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
import java.time.Clock;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Explicit, owner-scoped, digest-only session-summary anchoring command. */
@Service
public class MediaSessionSummaryAnchorService {

    private final MediaAccessService accessService;
    private final MediaSessionRepository sessionRepository;
    private final MediaSessionArtifactRepository artifactRepository;
    private final MediaMutationIdempotencyService idempotencyService;
    private final MediaTrustLedgerClient trustLedgerClient;
    private final MediaSecurityAuditService auditService;
    private final MediaMetrics metrics;

    public MediaSessionSummaryAnchorService(
            MediaAccessService accessService,
            MediaSessionRepository sessionRepository,
            MediaSessionArtifactRepository artifactRepository,
            MediaMutationIdempotencyService idempotencyService,
            MediaTrustLedgerClient trustLedgerClient,
            MediaSecurityAuditService auditService,
            MediaMetrics metrics,
            Clock clock) {
        this.accessService = accessService;
        this.sessionRepository = sessionRepository;
        this.artifactRepository = artifactRepository;
        this.idempotencyService = idempotencyService;
        this.trustLedgerClient = trustLedgerClient;
        this.auditService = auditService;
        this.metrics = metrics;
    }

    public MediaIdempotencyResult<MediaSessionAnchorResponse> anchor(
            MediaSubject subject, UUID sessionId, long expectedVersion, String idempotencyKey) {
        MediaSession session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null || !subject.accountId().equals(session.getOwnerAccountId())
                || !subject.tenantId().equals(session.getTenantId())) {
            throw new MediaResourceNotFoundException();
        }
        MediaSessionArtifact artifact = artifactRepository
                .findBySessionIdAndOwnerAccountIdAndTenantId(sessionId, subject.accountId(), subject.tenantId())
                .orElseThrow(MediaResourceNotFoundException::new);
        if (artifact.getVersion() != expectedVersion) {
            throw new MediaVersionConflictException();
        }
        accessService.authorize(
                subject,
                MediaAuthorizationActions.SESSION_UPDATE,
                MediaAuthorizationResource.existingSession(session.getId(), session.getOwnerAccountId(), subject));
        String digest = digest(artifact);
        AnchorCommand command = new AnchorCommand(artifact.getId(), artifact.getVersion(), digest);
        MediaIdempotencyResult<MediaSessionAnchorResponse> result = idempotencyService.execute(
                subject.accountId(),
                subject.tenantId(),
                MediaMutationOperation.SESSION_SUMMARY_ANCHOR,
                artifact.getId().toString(),
                idempotencyKey,
                command,
                expectedVersion,
                MediaSessionAnchorResponse.class,
                200,
                "/api/v1/media/sessions/" + sessionId + "/post-session/anchor",
                () -> MediaSessionAnchorResponse.from(trustLedgerClient.anchorSessionSummary(
                        subject,
                        artifact.getId(),
                        artifact.getVersion(),
                        digest,
                        "media-summary-anchor-" + artifact.getId() + "-" + artifact.getVersion())));
        auditService.record(
                subject,
                "media.session.summary_anchor",
                result.replayed() ? MediaAuditOutcome.REPLAYED : MediaAuditOutcome.SUCCESS,
                "media-session-artifact",
                artifact.getId(),
                null);
        metrics.record("session_summary_anchor", result.replayed() ? "replayed" : "success");
        return result;
    }

    private static String digest(MediaSessionArtifact artifact) {
        String canonical = "media-session-summary-v1\n"
                + "artifact=" + artifact.getId() + "\n"
                + "version=" + artifact.getVersion() + "\n"
                + "summary=" + artifact.getSummary() + "\n"
                + "action-items=" + artifact.getActionItemsJson();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private record AnchorCommand(UUID artifactId, long artifactVersion, String digestSha256) {
        private AnchorCommand {
            Objects.requireNonNull(artifactId, "artifactId must not be null");
            Objects.requireNonNull(digestSha256, "digestSha256 must not be null");
        }
    }
}
