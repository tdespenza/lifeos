package com.lifeos.media.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifeos.media.authorization.MediaAccessService;
import com.lifeos.media.authorization.MediaSubject;
import com.lifeos.media.domain.MediaAssetRepository;
import com.lifeos.media.domain.MediaMutationIdempotencyRepository;
import com.lifeos.media.domain.MediaSecurityAuditEventRepository;
import com.lifeos.media.domain.MediaSessionRepository;
import com.lifeos.media.domain.MediaSessionArtifactRepository;
import com.lifeos.media.domain.MediaSession;
import com.lifeos.media.service.MediaTaskGoalClient;
import com.lifeos.media.service.MediaTrustLedgerClient;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Executable HTTP contract for strict retries, private storage boundaries, and owner scope. */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:media-contract;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=media-contract-password",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration-h2",
    "media.idempotency-secret=contract-idempotency-secret-at-least-32-bytes",
    "media.audit-client-fingerprint-secret=contract-audit-secret-at-least-32-bytes",
    "media.development-signaling-secret=contract-signaling-secret-at-least-32-bytes",
    "media.session-expiry.enabled=false",
    "media.storage.mode=LOCAL_DEVELOPMENT",
    "media.signaling.mode=LOCAL_DEVELOPMENT",
    "identity.workload-token=contract-workload-token"
})
@AutoConfigureMockMvc
class MediaControllerContractTest {

    private static final String BEARER = "Bearer media-contract-token";
    private static final String ACCESS_TOKEN_PROOF =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final Path STORAGE_ROOT = Path.of(
            System.getProperty("java.io.tmpdir"), "lifeos-media-contract-" + UUID.randomUUID());

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MediaAssetRepository assetRepository;

    @Autowired
    private MediaSessionRepository sessionRepository;

    @Autowired
    private MediaSessionArtifactRepository artifactRepository;

    @Autowired
    private MediaMutationIdempotencyRepository idempotencyRepository;

    @Autowired
    private MediaSecurityAuditEventRepository auditRepository;

    @MockitoBean
    private MediaAccessService accessService;

    @MockitoBean
    private MediaTaskGoalClient taskGoalClient;

    @MockitoBean
    private MediaTrustLedgerClient trustLedgerClient;

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
        when(accessService.authenticate(anyString())).thenAnswer(invocation -> subject);
    }

    @Test
    void createsMetadataThenBoundedSourceAndReplaysTheExactSourceSnapshot() throws Exception {
        MvcResult create = createAsset("asset-metadata-contract-key", "Private coaching clip")
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.ETAG, "\"0\""))
                .andExpect(header().string(HttpHeaders.LOCATION, org.hamcrest.Matchers.matchesPattern("/api/v1/media/assets/.+")))
                .andExpect(jsonPath("$.sourceObjectReference").doesNotExist())
                .andReturn();
        JsonNode original = objectMapper.readTree(create.getResponse().getContentAsString());
        UUID assetId = UUID.fromString(original.path("id").asText());

        MvcResult uploaded = uploadSource(assetId, "source-upload-contract-key", "\"0\"")
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"1\""))
                .andExpect(jsonPath("$.status").value("STORED_AWAITING_EXTERNAL_PROCESSING"))
                .andExpect(jsonPath("$.sourceObjectReference").doesNotExist())
                .andReturn();
        JsonNode uploadedBody = objectMapper.readTree(uploaded.getResponse().getContentAsString());

        uploadSource(assetId, "source-upload-contract-key", "\"0\"")
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andExpect(header().string(HttpHeaders.ETAG, "\"1\""))
                .andExpect(result -> assertThat(objectMapper.readTree(result.getResponse().getContentAsString()))
                        .isEqualTo(uploadedBody));

        mockMvc.perform(get("/api/v1/media/assets/{assetId}/hls/master.m3u8", assetId)
                        .header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isConflict());
    }

    @Test
    void returnsTheSameGenericNotFoundBodyForMissingAndCrossOwnerAssets() throws Exception {
        UUID assetId = UUID.fromString(objectMapper.readTree(createAsset("asset-owner-contract-key", "Private clip")
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .path("id")
                .asText());
        subject = subject();

        MvcResult crossOwner = mockMvc.perform(get("/api/v1/media/assets/{assetId}", assetId)
                        .header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isNotFound())
                .andReturn();
        MvcResult missing = mockMvc.perform(get("/api/v1/media/assets/{assetId}", UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isNotFound())
                .andReturn();

        assertThat(crossOwner.getResponse().getContentAsString())
                .isEqualTo(missing.getResponse().getContentAsString())
                .doesNotContain(assetId.toString());
    }

    @Test
    void requiresStrictMutationHeadersAndUsesVersionedSessionLifecycle() throws Exception {
        UUID assetId = UUID.fromString(objectMapper.readTree(createAsset("asset-header-contract-key", "Header clip")
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .path("id")
                .asText());
        mockMvc.perform(multipart("/api/v1/media/assets/{assetId}/source", assetId)
                        .file(mp4File())
                        .with(request -> {
                            request.setMethod("PUT");
                            return request;
                        })
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("Idempotency-Key", "source-missing-if-match"))
                .andExpect(status().is(428));

        Instant start = Instant.now().plusSeconds(300);
        MvcResult session = mockMvc.perform(post("/api/v1/media/sessions")
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("Idempotency-Key", "session-create-contract-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sessionJson("Contract session", start, start.plusSeconds(1800))))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.ETAG, "\"0\""))
                .andReturn();
        UUID sessionId = UUID.fromString(objectMapper.readTree(session.getResponse().getContentAsString()).path("id").asText());
        mockMvc.perform(put("/api/v1/media/sessions/{sessionId}", sessionId)
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("Idempotency-Key", "session-update-contract-key")
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sessionJson("Contract session updated", start.plusSeconds(60), start.plusSeconds(1860))))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"1\""));

        Instant endedAt = Instant.now().minusSeconds(60);
        MediaSession ended = MediaSession.scheduled(
                UUID.randomUUID(),
                subject.accountId(),
                subject.tenantId(),
                com.lifeos.media.domain.MediaSessionKind.COACHING,
                "Completed contract session",
                endedAt.minusSeconds(1_800),
                endedAt,
                "America/Chicago",
                endedAt.minusSeconds(1_800));
        ended.endIfDue(endedAt.plusSeconds(1));
        sessionRepository.saveAndFlush(ended);

        mockMvc.perform(post("/api/v1/media/sessions/{sessionId}/post-session", ended.getId())
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("Idempotency-Key", "post-session-contract-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transcript\":\"ACTION: Send the plan. TODO: Book next session.\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.ETAG, "\"0\""))
                .andExpect(header().string(HttpHeaders.LOCATION,
                        "/api/v1/media/sessions/" + ended.getId() + "/post-session"))
                .andExpect(jsonPath("$.transcriptionMode").value("LOCAL_DETERMINISTIC_TEXT"))
                .andExpect(jsonPath("$.actionItems[0]").value("Send the plan."));

        mockMvc.perform(get("/api/v1/media/sessions/{sessionId}/post-session", ended.getId())
                        .header(HttpHeaders.AUTHORIZATION, BEARER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processingState").value("READY"));

        UUID anchorRequestId = UUID.randomUUID();
        when(trustLedgerClient.anchorSessionSummary(any(), any(), anyLong(), anyString(), anyString()))
                .thenReturn(new MediaTrustLedgerClient.AnchorResult(
                        anchorRequestId,
                        "MEDIA_SESSION_SUMMARY",
                        artifactRepository.findBySessionIdAndOwnerAccountIdAndTenantId(
                                        ended.getId(), subject.accountId(), subject.tenantId())
                                .orElseThrow()
                                .getId(),
                        0,
                        "b".repeat(64),
                        "CONFIRMED",
                        "0x" + "a".repeat(64),
                        11L,
                        endedAt));
        mockMvc.perform(post("/api/v1/media/sessions/{sessionId}/post-session/anchor", ended.getId())
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("Idempotency-Key", "session-summary-anchor-contract-key")
                        .header(HttpHeaders.IF_MATCH, "\"0\""))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"0\""))
                .andExpect(header().string(HttpHeaders.LOCATION,
                        "/api/v1/media/sessions/" + ended.getId() + "/post-session/anchor"))
                .andExpect(jsonPath("$.requestId").value(anchorRequestId.toString()))
                .andExpect(jsonPath("$.state").value("CONFIRMED"));

        UUID taskId = UUID.randomUUID();
        when(taskGoalClient.createTask(any(), anyString(), any(), any(), anyString()))
                .thenReturn(new MediaTaskGoalClient.TaskCreationResult(
                        taskId, "Send the plan.", "ACTIVE", 0, endedAt, endedAt, null, null, 3, null));
        mockMvc.perform(post("/api/v1/media/sessions/{sessionId}/post-session/tasks", ended.getId())
                        .header(HttpHeaders.AUTHORIZATION, BEARER)
                        .header("Idempotency-Key", "confirmed-action-contract-key")
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actionItem\":\"Send the plan.\",\"priority\":3}"))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.ETAG, "\"0\""))
                .andExpect(header().string(HttpHeaders.LOCATION, "/api/v1/tasks/" + taskId))
                .andExpect(jsonPath("$.id").value(taskId.toString()));
    }

    private org.springframework.test.web.servlet.ResultActions createAsset(String key, String title) throws Exception {
        return mockMvc.perform(post("/api/v1/media/assets")
                .header(HttpHeaders.AUTHORIZATION, BEARER)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"" + title + "\"}"));
    }

    private org.springframework.test.web.servlet.ResultActions uploadSource(UUID assetId, String key, String ifMatch)
            throws Exception {
        return mockMvc.perform(multipart("/api/v1/media/assets/{assetId}/source", assetId)
                .file(mp4File())
                .with(request -> {
                    request.setMethod("PUT");
                    return request;
                })
                .header(HttpHeaders.AUTHORIZATION, BEARER)
                .header("Idempotency-Key", key)
                .header(HttpHeaders.IF_MATCH, ifMatch));
    }

    private static MockMultipartFile mp4File() {
        return new MockMultipartFile(
                "file", "client-controlled-name.mp4", "video/mp4", new byte[] {0, 0, 0, 16, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm', 0, 0, 0, 0});
    }

    private static String sessionJson(String title, Instant start, Instant end) {
        return "{\"kind\":\"COACHING\",\"title\":\"" + title + "\",\"scheduledStartAt\":\"" + start
                + "\",\"scheduledEndAt\":\"" + end + "\",\"timeZone\":\"America/Chicago\"}";
    }

    private static MediaSubject subject() {
        return new MediaSubject(UUID.randomUUID(), UUID.randomUUID(), "password", ACCESS_TOKEN_PROOF);
    }
}
