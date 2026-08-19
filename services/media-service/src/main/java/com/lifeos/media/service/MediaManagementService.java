package com.lifeos.media.service;

import com.lifeos.media.api.CreateMediaAssetRequest;
import com.lifeos.media.api.CreateMediaSessionRequest;
import com.lifeos.media.api.MediaAssetResponse;
import com.lifeos.media.api.MediaJoinResponse;
import com.lifeos.media.api.MediaSessionResponse;
import com.lifeos.media.api.UpdateMediaSessionRequest;
import com.lifeos.media.audit.MediaSecurityAuditService;
import com.lifeos.media.authorization.MediaAccessService;
import com.lifeos.media.authorization.MediaAuthorizationActions;
import com.lifeos.media.authorization.MediaAuthorizationDenied;
import com.lifeos.media.authorization.MediaAuthorizationResource;
import com.lifeos.media.authorization.MediaSubject;
import com.lifeos.media.config.MediaProperties;
import com.lifeos.media.domain.MediaAsset;
import com.lifeos.media.domain.MediaAssetRepository;
import com.lifeos.media.domain.MediaAssetStatus;
import com.lifeos.media.domain.MediaAuditOutcome;
import com.lifeos.media.domain.MediaSession;
import com.lifeos.media.domain.MediaSessionRepository;
import com.lifeos.media.idempotency.MediaIdempotencyResult;
import com.lifeos.media.idempotency.MediaMutationIdempotencyService;
import com.lifeos.media.idempotency.MediaMutationOperation;
import com.lifeos.media.processing.MediaProcessingGateway;
import com.lifeos.media.signaling.MediaSignalingGateway;
import com.lifeos.media.signaling.MediaSignalingPermit;
import com.lifeos.media.storage.MediaContentType;
import com.lifeos.media.storage.MediaHlsNotReadyException;
import com.lifeos.media.storage.MediaObjectStore;
import com.lifeos.media.storage.MediaReadObject;
import com.lifeos.media.storage.StagedMediaObject;
import com.lifeos.media.storage.StoredMediaObject;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owner-scoped media control plane. It never returns object-store references, creates HLS, or
 * pretends that an external signaling/SFU provider exists beyond its selected adapter.
 */
@Service
public class MediaManagementService {

    private final MediaAccessService accessService;
    private final MediaAssetRepository assetRepository;
    private final MediaSessionRepository sessionRepository;
    private final MediaObjectStore objectStore;
    private final MediaSignalingGateway signalingGateway;
    private final MediaProcessingGateway processingGateway;
    private final MediaMutationIdempotencyService idempotencyService;
    private final MediaSecurityAuditService auditService;
    private final MediaMetrics metrics;
    private final MediaProperties properties;
    private final Clock clock;

    public MediaManagementService(
            MediaAccessService accessService,
            MediaAssetRepository assetRepository,
            MediaSessionRepository sessionRepository,
            MediaObjectStore objectStore,
            MediaSignalingGateway signalingGateway,
            MediaProcessingGateway processingGateway,
            MediaMutationIdempotencyService idempotencyService,
            MediaSecurityAuditService auditService,
            MediaMetrics metrics,
            MediaProperties properties,
            Clock clock) {
        this.accessService = accessService;
        this.assetRepository = assetRepository;
        this.sessionRepository = sessionRepository;
        this.objectStore = objectStore;
        this.signalingGateway = signalingGateway;
        this.processingGateway = processingGateway;
        this.idempotencyService = idempotencyService;
        this.auditService = auditService;
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;
    }

    public MediaIdempotencyResult<MediaAssetResponse> createAsset(
            MediaSubject subject, CreateMediaAssetRequest request, String idempotencyKey) {
        Objects.requireNonNull(subject, "subject must not be null");
        Objects.requireNonNull(request, "request must not be null");
        UUID assetId = UUID.randomUUID();
        authorize(
                subject,
                MediaAuthorizationActions.ASSET_CREATE,
                MediaAuthorizationResource.newAsset(assetId, subject),
                "media-asset",
                assetId);
        MediaIdempotencyResult<MediaAssetResponse> result = idempotencyService.execute(
                subject.accountId(),
                subject.tenantId(),
                MediaMutationOperation.ASSET_CREATE,
                "asset:create",
                idempotencyKey,
                request,
                null,
                MediaAssetResponse.class,
                201,
                "/api/v1/media/assets/" + assetId,
                () -> {
                    MediaAsset asset = assetRepository.saveAndFlush(
                            MediaAsset.pending(assetId, subject.accountId(), subject.tenantId(), request.title(), clock.instant()));
                    return MediaAssetResponse.from(asset);
                });
        auditService.record(
                subject,
                "media.asset.create",
                result.replayed() ? MediaAuditOutcome.REPLAYED : MediaAuditOutcome.SUCCESS,
                "media-asset",
                result.body().id(),
                null);
        metrics.record("asset_create", result.replayed() ? "replayed" : "success");
        return result;
    }

    /** Stages bounded bytes before the durable retry reservation; matching retries only replay metadata. */
    public MediaIdempotencyResult<MediaAssetResponse> uploadAssetSource(
            MediaSubject subject,
            UUID assetId,
            long expectedVersion,
            String contentTypeHeader,
            InputStream input,
            String idempotencyKey) {
        Objects.requireNonNull(subject, "subject must not be null");
        Objects.requireNonNull(assetId, "assetId must not be null");
        // Do not reject based on current status before the idempotency reservation: a matching
        // network retry arrives after the first successful upload changed it to STORED.
        loadAsset(subject, assetId, MediaAuthorizationActions.ASSET_UPLOAD);
        MediaContentType contentType = MediaContentType.requireAllowed(contentTypeHeader);
        StagedMediaObject staged = objectStore.stage(
                input, contentType, properties.getMaxUploadBytes(), properties.getUploadDeadline());
        AtomicReference<String> promotedReference = new AtomicReference<>();
        try {
            AssetUploadCommand command = new AssetUploadCommand(
                    assetId,
                    expectedVersion,
                    staged.checksumSha256(),
                    staged.contentLength(),
                    staged.contentType().mediaType());
            MediaIdempotencyResult<MediaAssetResponse> result = idempotencyService.execute(
                    subject.accountId(),
                    subject.tenantId(),
                    MediaMutationOperation.ASSET_UPLOAD,
                    assetId.toString(),
                    idempotencyKey,
                    command,
                    expectedVersion,
                    MediaAssetResponse.class,
                    200,
                    "/api/v1/media/assets/" + assetId,
                    () -> completeAssetUpload(subject, assetId, expectedVersion, staged, promotedReference));
            auditService.record(
                    subject,
                    "media.asset.upload",
                    result.replayed() ? MediaAuditOutcome.REPLAYED : MediaAuditOutcome.SUCCESS,
                    "media-asset",
                    assetId,
                    null);
            metrics.record("asset_upload", result.replayed() ? "replayed" : "success");
            if (!result.replayed()
                    && properties.getProcessing().getMode() == com.lifeos.media.config.MediaProcessingMode.LOCAL_DEVELOPMENT) {
                assetRepository.findById(assetId).ifPresent(processingGateway::requestHlsProcessing);
            }
            return result;
        } catch (RuntimeException exception) {
            // A failed DB commit after a local promotion has an indeterminate outcome; retaining a
            // generated orphan is safer than deleting bytes referenced by a committed row.
            metrics.record("asset_upload", "failed");
            throw exception;
        } finally {
            objectStore.discard(staged);
        }
    }

    @Transactional(readOnly = true)
    public List<MediaAssetResponse> listAssets(MediaSubject subject, int limit) {
        authorize(
                subject,
                MediaAuthorizationActions.ASSET_LIST,
                MediaAuthorizationResource.collection(subject),
                "media",
                null);
        List<MediaAssetResponse> result = assetRepository
                .findByTenantIdAndOwnerAccountIdOrderByCreatedAtDescIdDesc(subject.tenantId(), subject.accountId(), pageRequest(limit))
                .stream()
                .map(MediaAssetResponse::from)
                .toList();
        auditService.record(subject, "media.asset.list", MediaAuditOutcome.SUCCESS, "media", null, null);
        metrics.record("asset_list", "success");
        return result;
    }

    @Transactional(readOnly = true)
    public MediaAssetResponse getAsset(MediaSubject subject, UUID assetId) {
        MediaAsset asset = loadAsset(subject, assetId, MediaAuthorizationActions.ASSET_READ);
        MediaAssetResponse result = MediaAssetResponse.from(asset);
        auditService.record(subject, "media.asset.read", MediaAuditOutcome.SUCCESS, "media-asset", assetId, null);
        metrics.record("asset_read", "success");
        return result;
    }

    @Transactional(readOnly = true)
    public MediaReadObject openHlsManifest(MediaSubject subject, UUID assetId) {
        MediaAsset asset = loadAsset(subject, assetId, MediaAuthorizationActions.HLS_MANIFEST_READ);
        return openHls(subject, asset, null);
    }

    @Transactional(readOnly = true)
    public MediaReadObject openHlsSegment(MediaSubject subject, UUID assetId, String segmentName) {
        MediaAsset asset = loadAsset(subject, assetId, MediaAuthorizationActions.HLS_SEGMENT_READ);
        return openHls(subject, asset, segmentName);
    }

    public MediaIdempotencyResult<MediaSessionResponse> createSession(
            MediaSubject subject, CreateMediaSessionRequest request, String idempotencyKey) {
        Objects.requireNonNull(subject, "subject must not be null");
        Objects.requireNonNull(request, "request must not be null");
        validateSchedule(request.scheduledStartAt(), request.scheduledEndAt(), request.timeZone());
        UUID sessionId = UUID.randomUUID();
        authorize(
                subject,
                MediaAuthorizationActions.SESSION_CREATE,
                MediaAuthorizationResource.newSession(sessionId, subject),
                "media-session",
                sessionId);
        MediaIdempotencyResult<MediaSessionResponse> result = idempotencyService.execute(
                subject.accountId(),
                subject.tenantId(),
                MediaMutationOperation.SESSION_CREATE,
                "session:create",
                idempotencyKey,
                request,
                null,
                MediaSessionResponse.class,
                201,
                "/api/v1/media/sessions/" + sessionId,
                () -> MediaSessionResponse.from(sessionRepository.saveAndFlush(MediaSession.scheduled(
                        sessionId,
                        subject.accountId(),
                        subject.tenantId(),
                        request.kind(),
                        request.title(),
                        request.scheduledStartAt(),
                        request.scheduledEndAt(),
                        request.timeZone(),
                        clock.instant())),
                        clock.instant()));
        auditService.record(
                subject,
                "media.session.create",
                result.replayed() ? MediaAuditOutcome.REPLAYED : MediaAuditOutcome.SUCCESS,
                "media-session",
                result.body().id(),
                null);
        metrics.record("session_create", result.replayed() ? "replayed" : "success");
        return result;
    }

    @Transactional(readOnly = true)
    public List<MediaSessionResponse> listSessions(MediaSubject subject, int limit) {
        authorize(
                subject,
                MediaAuthorizationActions.SESSION_LIST,
                MediaAuthorizationResource.collection(subject),
                "media",
                null);
        List<MediaSessionResponse> result = sessionRepository
                .findByTenantIdAndOwnerAccountIdOrderByScheduledStartAtAscIdAsc(
                        subject.tenantId(), subject.accountId(), pageRequest(limit))
                .stream()
                .map(session -> MediaSessionResponse.from(session, clock.instant()))
                .toList();
        auditService.record(subject, "media.session.list", MediaAuditOutcome.SUCCESS, "media", null, null);
        metrics.record("session_list", "success");
        return result;
    }

    @Transactional(readOnly = true)
    public MediaSessionResponse getSession(MediaSubject subject, UUID sessionId) {
        MediaSession session = loadSession(subject, sessionId, MediaAuthorizationActions.SESSION_READ);
        MediaSessionResponse result = MediaSessionResponse.from(session, clock.instant());
        auditService.record(subject, "media.session.read", MediaAuditOutcome.SUCCESS, "media-session", sessionId, null);
        metrics.record("session_read", "success");
        return result;
    }

    public MediaIdempotencyResult<MediaSessionResponse> updateSession(
            MediaSubject subject,
            UUID sessionId,
            long expectedVersion,
            UpdateMediaSessionRequest request,
            String idempotencyKey) {
        Objects.requireNonNull(request, "request must not be null");
        validateSchedule(request.scheduledStartAt(), request.scheduledEndAt(), request.timeZone());
        loadSession(subject, sessionId, MediaAuthorizationActions.SESSION_UPDATE);
        MediaIdempotencyResult<MediaSessionResponse> result = idempotencyService.execute(
                subject.accountId(),
                subject.tenantId(),
                MediaMutationOperation.SESSION_UPDATE,
                sessionId.toString(),
                idempotencyKey,
                request,
                expectedVersion,
                MediaSessionResponse.class,
                200,
                "/api/v1/media/sessions/" + sessionId,
                () -> updateSessionOnce(subject, sessionId, expectedVersion, request));
        auditService.record(
                subject,
                "media.session.update",
                result.replayed() ? MediaAuditOutcome.REPLAYED : MediaAuditOutcome.SUCCESS,
                "media-session",
                sessionId,
                null);
        metrics.record("session_update", result.replayed() ? "replayed" : "success");
        return result;
    }

    public MediaIdempotencyResult<MediaSessionResponse> cancelSession(
            MediaSubject subject, UUID sessionId, long expectedVersion, String idempotencyKey) {
        loadSession(subject, sessionId, MediaAuthorizationActions.SESSION_CANCEL);
        SessionCancelCommand command = new SessionCancelCommand(sessionId, expectedVersion);
        MediaIdempotencyResult<MediaSessionResponse> result = idempotencyService.execute(
                subject.accountId(),
                subject.tenantId(),
                MediaMutationOperation.SESSION_CANCEL,
                sessionId.toString(),
                idempotencyKey,
                command,
                expectedVersion,
                MediaSessionResponse.class,
                200,
                "/api/v1/media/sessions/" + sessionId,
                () -> cancelSessionOnce(subject, sessionId, expectedVersion));
        auditService.record(
                subject,
                "media.session.cancel",
                result.replayed() ? MediaAuditOutcome.REPLAYED : MediaAuditOutcome.SUCCESS,
                "media-session",
                sessionId,
                null);
        metrics.record("session_cancel", result.replayed() ? "replayed" : "success");
        return result;
    }

    /** Joining is intentionally owner-only today and reissues a short credential rather than mutating durable state. */
    public MediaJoinResponse joinSession(MediaSubject subject, UUID sessionId) {
        MediaSession session = loadSession(subject, sessionId, MediaAuthorizationActions.SESSION_JOIN);
        Instant now = clock.instant();
        if (!session.isJoinableAt(now)) {
            auditService.record(
                    subject, "media.session.join", MediaAuditOutcome.DENIED, "media-session", sessionId, "NOT_JOINABLE");
            metrics.record("session_join", "not_joinable");
            throw new MediaSessionNotJoinableException();
        }
        MediaSignalingPermit permit = signalingGateway.issuePermit(session, subject.accountId(), now);
        MediaJoinResponse result = MediaJoinResponse.from(sessionId, permit);
        auditService.record(subject, "media.session.join", MediaAuditOutcome.SUCCESS, "media-session", sessionId, null);
        metrics.record("session_join", "success");
        return result;
    }

    private MediaAssetResponse completeAssetUpload(
            MediaSubject subject,
            UUID assetId,
            long expectedVersion,
            StagedMediaObject staged,
            AtomicReference<String> promotedReference) {
        MediaAsset asset = assetRepository.findByIdForUpdate(assetId).orElseThrow(MediaResourceNotFoundException::new);
        assertOwner(asset.getOwnerAccountId(), asset.getTenantId(), subject);
        verifyVersion(asset.getVersion(), expectedVersion);
        StoredMediaObject stored = objectStore.promote(staged, assetId);
        promotedReference.set(stored.objectReference());
        asset.completeUpload(
                stored.objectReference(),
                staged.checksumSha256(),
                staged.contentLength(),
                staged.contentType().mediaType(),
                clock.instant());
        assetRepository.flush();
        return MediaAssetResponse.from(asset);
    }

    private MediaSessionResponse updateSessionOnce(
            MediaSubject subject, UUID sessionId, long expectedVersion, UpdateMediaSessionRequest request) {
        MediaSession session = sessionRepository.findByIdForUpdate(sessionId).orElseThrow(MediaResourceNotFoundException::new);
        assertOwner(session.getOwnerAccountId(), session.getTenantId(), subject);
        verifyVersion(session.getVersion(), expectedVersion);
        session.reschedule(
                request.kind(),
                request.title(),
                request.scheduledStartAt(),
                request.scheduledEndAt(),
                request.timeZone(),
                clock.instant());
        sessionRepository.flush();
        return MediaSessionResponse.from(session, clock.instant());
    }

    private MediaSessionResponse cancelSessionOnce(MediaSubject subject, UUID sessionId, long expectedVersion) {
        MediaSession session = sessionRepository.findByIdForUpdate(sessionId).orElseThrow(MediaResourceNotFoundException::new);
        assertOwner(session.getOwnerAccountId(), session.getTenantId(), subject);
        verifyVersion(session.getVersion(), expectedVersion);
        session.cancel(clock.instant());
        sessionRepository.flush();
        return MediaSessionResponse.from(session, clock.instant());
    }

    private MediaReadObject openHls(MediaSubject subject, MediaAsset asset, String segmentName) {
        if (asset.getStatus() != MediaAssetStatus.HLS_READY || asset.getHlsManifestReference() == null) {
            auditService.record(
                    subject, "media.hls.read", MediaAuditOutcome.DENIED, "media-asset", asset.getId(), "HLS_NOT_READY");
            metrics.record("hls_read", "not_ready");
            throw new MediaHlsNotReadyException();
        }
        MediaReadObject result = segmentName == null
                ? objectStore.openHlsManifest(asset.getHlsManifestReference())
                : objectStore.openHlsSegment(asset.getHlsManifestReference(), segmentName);
        try {
            auditService.record(subject, "media.hls.read", MediaAuditOutcome.SUCCESS, "media-asset", asset.getId(), null);
            metrics.record("hls_read", "success");
            return result;
        } catch (RuntimeException exception) {
            closeQuietly(result);
            throw exception;
        }
    }

    private MediaAsset loadAsset(MediaSubject subject, UUID assetId, String action) {
        MediaAsset asset = assetRepository.findById(assetId).orElse(null);
        if (asset == null) {
            throw noDisclosure(subject, action, "media-asset", assetId, "RESOURCE_NOT_FOUND");
        }
        if (!isOwner(asset.getOwnerAccountId(), asset.getTenantId(), subject)) {
            throw noDisclosure(subject, action, "media-asset", assetId, "OWNER_SCOPE_MISMATCH");
        }
        authorize(
                subject,
                action,
                MediaAuthorizationResource.existingAsset(assetId, asset.getOwnerAccountId(), subject),
                "media-asset",
                assetId);
        assertOwner(asset.getOwnerAccountId(), asset.getTenantId(), subject);
        return asset;
    }

    private MediaSession loadSession(MediaSubject subject, UUID sessionId, String action) {
        MediaSession session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null) {
            throw noDisclosure(subject, action, "media-session", sessionId, "RESOURCE_NOT_FOUND");
        }
        if (!isOwner(session.getOwnerAccountId(), session.getTenantId(), subject)) {
            throw noDisclosure(subject, action, "media-session", sessionId, "OWNER_SCOPE_MISMATCH");
        }
        authorize(
                subject,
                action,
                MediaAuthorizationResource.existingSession(sessionId, session.getOwnerAccountId(), subject),
                "media-session",
                sessionId);
        assertOwner(session.getOwnerAccountId(), session.getTenantId(), subject);
        return session;
    }

    private void authorize(
            MediaSubject subject,
            String action,
            MediaAuthorizationResource resource,
            String targetType,
            UUID targetId) {
        try {
            accessService.authorize(subject, action, resource);
        } catch (MediaAuthorizationDenied exception) {
            auditService.record(subject, action, MediaAuditOutcome.DENIED, targetType, targetId, "IDENTITY_DENIED");
            metrics.record("authorization", "denied");
            throw exception;
        }
    }

    private void validateSchedule(Instant start, Instant end, String timeZone) {
        if (start == null || end == null || !end.isAfter(start)) {
            throw new IllegalArgumentException("media session interval is invalid");
        }
        Instant now = clock.instant();
        if (start.isBefore(now) || start.isAfter(now.plus(properties.getSignaling().getMaxScheduleAhead()))) {
            throw new IllegalArgumentException("media session start is outside the allowed horizon");
        }
        if (Duration.between(start, end).compareTo(properties.getSignaling().getMaxSessionDuration()) > 0) {
            throw new IllegalArgumentException("media session duration exceeds the allowed bound");
        }
        if (timeZone == null || timeZone.isBlank()) {
            throw new IllegalArgumentException("media session timeZone is required");
        }
    }

    private static void verifyVersion(long actual, long expected) {
        if (actual != expected) {
            throw new MediaVersionConflictException();
        }
    }

    private static void assertOwner(UUID ownerAccountId, String tenantId, MediaSubject subject) {
        if (!isOwner(ownerAccountId, tenantId, subject)) {
            throw new MediaResourceNotFoundException();
        }
    }

    private MediaResourceNotFoundException noDisclosure(
            MediaSubject subject, String action, String targetType, UUID targetId, String reasonCode) {
        auditService.record(subject, action, MediaAuditOutcome.DENIED, targetType, targetId, reasonCode);
        metrics.record("owner_scope", "denied");
        return new MediaResourceNotFoundException();
    }

    private static boolean isOwner(UUID ownerAccountId, String tenantId, MediaSubject subject) {
        return ownerAccountId != null
                && subject.accountId().equals(ownerAccountId)
                && subject.tenantId().equals(tenantId)
                && tenantId.equals(ownerAccountId.toString());
    }

    private static PageRequest pageRequest(int limit) {
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("media list limit must be between one and 200");
        }
        return PageRequest.of(0, limit);
    }

    private static void closeQuietly(MediaReadObject object) {
        try {
            object.close();
        } catch (Exception ignored) {
            // The audit persistence error remains the safe public fact.
        }
    }

    private record AssetUploadCommand(
            UUID assetId, long expectedVersion, String checksumSha256, long contentLength, String contentType) {
    }

    private record SessionCancelCommand(UUID sessionId, long expectedVersion) {
    }
}
