package com.lifeos.media.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lifeos.media.api.CreateMediaAssetRequest;
import com.lifeos.media.api.CreateMediaSessionRequest;
import com.lifeos.media.api.ConfirmSessionActionRequest;
import com.lifeos.media.api.MediaAssetResponse;
import com.lifeos.media.api.MediaSessionResponse;
import com.lifeos.media.api.PostSessionArtifactRequest;
import com.lifeos.media.api.UpdateMediaSessionRequest;
import com.lifeos.media.authorization.MediaAccessService;
import com.lifeos.media.authorization.MediaSubject;
import com.lifeos.media.domain.MediaAssetRepository;
import com.lifeos.media.domain.MediaAssetStatus;
import com.lifeos.media.domain.MediaMutationIdempotencyRepository;
import com.lifeos.media.domain.MediaSecurityAuditEventRepository;
import com.lifeos.media.domain.MediaSessionRepository;
import com.lifeos.media.domain.MediaSessionArtifactRepository;
import com.lifeos.media.domain.MediaSession;
import com.lifeos.media.idempotency.MediaIdempotencyResult;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** H2 integration coverage for owner scope, upload replay, versioning, and join permits. */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:media-service-integration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=media-integration-password",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration-h2",
    "media.idempotency-secret=integration-idempotency-secret-at-least-32-bytes",
    "media.audit-client-fingerprint-secret=integration-audit-secret-at-least-32-bytes",
    "media.development-signaling-secret=integration-signaling-secret-at-least-32-bytes",
    "media.session-expiry.enabled=false",
    "media.storage.mode=LOCAL_DEVELOPMENT",
    "media.signaling.mode=LOCAL_DEVELOPMENT",
    "identity.workload-token=integration-workload-token"
})
class MediaServiceIntegrationTest {

    private static final String ACCESS_TOKEN_PROOF =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final Path STORAGE_ROOT = Path.of(
            System.getProperty("java.io.tmpdir"), "lifeos-media-integration-" + UUID.randomUUID());

    @Autowired
    private MediaManagementService service;

    @Autowired
    private MediaAssetRepository assetRepository;

    @Autowired
    private MediaSessionRepository sessionRepository;

    @Autowired
    private MediaSessionArtifactRepository artifactRepository;

    @Autowired
    private MediaPostSessionService postSessionService;

    @Autowired
    private MediaFollowUpTaskService followUpTaskService;

    @Autowired
    private MediaMutationIdempotencyRepository idempotencyRepository;

    @Autowired
    private MediaSecurityAuditEventRepository auditRepository;

    @MockitoBean
    private MediaAccessService accessService;

    @MockitoBean
    private MediaTaskGoalClient taskGoalClient;

    private MediaSubject subject;

    @DynamicPropertySource
    static void localStorage(DynamicPropertyRegistry registry) {
        registry.add("media.storage.local-root", () -> STORAGE_ROOT.toString());
    }

    @BeforeEach
    void setUp() {
        auditRepository.deleteAll();
        idempotencyRepository.deleteAll();
        assetRepository.deleteAll();
        artifactRepository.deleteAll();
        sessionRepository.deleteAll();
        subject = subject();
    }

    @Test
    void durableMetadataAndSourceRetriesReturnTheOriginalAssetSnapshot() {
        MediaIdempotencyResult<MediaAssetResponse> created = service.createAsset(
                subject, new CreateMediaAssetRequest("Private coaching clip"), "asset-create-replay-key");
        MediaIdempotencyResult<MediaAssetResponse> createReplay = service.createAsset(
                subject, new CreateMediaAssetRequest("Private coaching clip"), "asset-create-replay-key");

        MediaIdempotencyResult<MediaAssetResponse> uploaded = service.uploadAssetSource(
                subject,
                created.body().id(),
                created.body().version(),
                "video/mp4",
                new ByteArrayInputStream(mp4()),
                "asset-source-replay-key");
        MediaIdempotencyResult<MediaAssetResponse> uploadReplay = service.uploadAssetSource(
                subject,
                created.body().id(),
                created.body().version(),
                "video/mp4",
                new ByteArrayInputStream(mp4()),
                "asset-source-replay-key");

        assertThat(created.replayed()).isFalse();
        assertThat(createReplay.replayed()).isTrue();
        assertThat(createReplay.body()).isEqualTo(created.body());
        assertThat(uploaded.body().status()).isEqualTo(MediaAssetStatus.STORED_AWAITING_EXTERNAL_PROCESSING);
        assertThat(uploaded.body().version()).isEqualTo(1L);
        assertThat(uploadReplay.replayed()).isTrue();
        assertThat(uploadReplay.body()).isEqualTo(uploaded.body());
        assertThat(assetRepository.count()).isEqualTo(1L);
        assertThat(idempotencyRepository.count()).isEqualTo(2L);
    }

    @Test
    void keepsAssetsAndSessionsNoDisclosureOwnerScopedEvenIfTheDecisionClientAllows() {
        MediaAssetResponse asset = service.createAsset(
                        subject, new CreateMediaAssetRequest("Private journal clip"), "asset-owner-scope-key")
                .body();
        MediaSubject other = subject();

        assertThatThrownBy(() -> service.getAsset(other, asset.id())).isInstanceOf(MediaResourceNotFoundException.class);

        Instant start = Instant.now().plusSeconds(300);
        MediaSessionResponse session = service.createSession(
                        subject,
                        new CreateMediaSessionRequest(
                                com.lifeos.media.domain.MediaSessionKind.COACHING,
                                "Private session",
                                start,
                                start.plusSeconds(1800),
                                "America/Chicago"),
                        "session-owner-scope-key")
                .body();
        assertThatThrownBy(() -> service.getSession(other, session.id())).isInstanceOf(MediaResourceNotFoundException.class);
    }

    @Test
    void versionedSessionLifecycleReplaysCancellationAndIssuesOnlyABoundedLocalPermit() {
        Instant start = Instant.now().plusSeconds(300);
        MediaSessionResponse created = service.createSession(
                        subject,
                        new CreateMediaSessionRequest(
                                com.lifeos.media.domain.MediaSessionKind.COACHING,
                                "Practice session",
                                start,
                                start.plusSeconds(1800),
                                "America/Chicago"),
                        "session-create-key")
                .body();
        MediaIdempotencyResult<MediaSessionResponse> updated = service.updateSession(
                subject,
                created.id(),
                created.version(),
                new UpdateMediaSessionRequest(
                        com.lifeos.media.domain.MediaSessionKind.COACHING,
                        "Practice session updated",
                        start.plusSeconds(60),
                        start.plusSeconds(1860),
                        "America/Chicago"),
                "session-update-key");
        var join = service.joinSession(subject, created.id());
        MediaIdempotencyResult<MediaSessionResponse> cancelled = service.cancelSession(
                subject, created.id(), updated.body().version(), "session-cancel-key");
        MediaIdempotencyResult<MediaSessionResponse> replay = service.cancelSession(
                subject, created.id(), updated.body().version(), "session-cancel-key");

        assertThat(updated.body().version()).isEqualTo(1L);
        assertThat(join.mode()).isEqualTo("LOCAL_DEVELOPMENT");
        assertThat(join.credential()).doesNotContain(created.id().toString());
        assertThat(cancelled.body().status()).isEqualTo(com.lifeos.media.domain.MediaSessionStatus.CANCELLED);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.body()).isEqualTo(cancelled.body());
    }

    @Test
    void postSessionProcessingIsOwnerScopedDeterministicAndExactlyReplayed() {
        Instant end = Instant.now().minusSeconds(60);
        MediaSession ended = MediaSession.scheduled(
                UUID.randomUUID(),
                subject.accountId(),
                subject.tenantId(),
                com.lifeos.media.domain.MediaSessionKind.COACHING,
                "Completed session",
                end.minusSeconds(1800),
                end,
                "America/Chicago",
                end.minusSeconds(1800));
        ended.endIfDue(end.plusSeconds(1));
        sessionRepository.saveAndFlush(ended);

        PostSessionArtifactRequest request = new PostSessionArtifactRequest(
                "We reviewed priorities. ACTION: Send the plan. TODO: Book the next session.");
        var first = postSessionService.process(subject, ended.getId(), request, "post-session-replay-key");
        var replay = postSessionService.process(subject, ended.getId(), request, "post-session-replay-key");

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.body()).isEqualTo(first.body());
        assertThat(first.body().transcriptionMode()).isEqualTo("LOCAL_DETERMINISTIC_TEXT");
        assertThat(first.body().processingState()).isEqualTo("READY");
        assertThat(first.body().actionItems()).containsExactly("Send the plan.", "Book the next session.");
        assertThat(artifactRepository.count()).isEqualTo(1L);

        MediaSubject other = subject();
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> postSessionService.get(other, ended.getId()))
                .isInstanceOf(MediaResourceNotFoundException.class);
    }

    @Test
    void explicitlyConfirmedActionItemCreatesAndReplaysOneTaskGoalCommand() {
        Instant end = Instant.now().minusSeconds(60);
        MediaSession ended = MediaSession.scheduled(
                UUID.randomUUID(), subject.accountId(), subject.tenantId(),
                com.lifeos.media.domain.MediaSessionKind.COACHING, "Completed session",
                end.minusSeconds(1_800), end, "America/Chicago", end.minusSeconds(1_800));
        ended.endIfDue(end.plusSeconds(1));
        sessionRepository.saveAndFlush(ended);
        postSessionService.process(subject, ended.getId(),
                new PostSessionArtifactRequest("ACTION: Send the plan."), "artifact-for-task-key");

        UUID taskId = UUID.randomUUID();
        when(taskGoalClient.createTask(any(), anyString(), any(), any(), anyString()))
                .thenReturn(new MediaTaskGoalClient.TaskCreationResult(
                        taskId, "Send the plan.", "ACTIVE", 0, end, end, null, null, 2, null));
        ConfirmSessionActionRequest request = new ConfirmSessionActionRequest("Send the plan.", 2, null);
        var first = followUpTaskService.confirm(subject, ended.getId(), 0, request, "follow-up-task-replay-key");
        var replay = followUpTaskService.confirm(subject, ended.getId(), 0, request, "follow-up-task-second-key");

        assertThat(first.replayed()).isFalse();
        assertThat(first.body().id()).isEqualTo(taskId);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.body()).isEqualTo(first.body());
        verify(taskGoalClient).createTask(any(), anyString(), any(), any(), anyString());
    }

    private static byte[] mp4() {
        return new byte[] {0, 0, 0, 16, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm', 0, 0, 0, 0};
    }

    private static MediaSubject subject() {
        return new MediaSubject(UUID.randomUUID(), UUID.randomUUID(), "password", ACCESS_TOKEN_PROOF);
    }
}
