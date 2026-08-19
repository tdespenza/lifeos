package com.lifeos.media.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import com.lifeos.media.api.CreateMediaAssetRequest;
import com.lifeos.media.api.MediaAssetResponse;
import com.lifeos.media.authorization.MediaAccessService;
import com.lifeos.media.authorization.MediaSubject;
import com.lifeos.media.domain.MediaAssetRepository;
import com.lifeos.media.domain.MediaMutationIdempotencyRepository;
import com.lifeos.media.domain.MediaSecurityAuditEventRepository;
import com.lifeos.media.domain.MediaSessionRepository;
import com.lifeos.media.service.MediaManagementService;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** PostgreSQL race coverage for the durable create reservation; skipped when Docker is unavailable. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true",
    "media.idempotency-secret=postgres-idempotency-secret-at-least-32-bytes",
    "media.audit-client-fingerprint-secret=postgres-audit-secret-at-least-32-bytes",
    "media.development-signaling-secret=postgres-signaling-secret-at-least-32-bytes",
    "media.session-expiry.enabled=false",
    "media.storage.mode=LOCAL_DEVELOPMENT",
    "media.signaling.mode=LOCAL_DEVELOPMENT",
    "identity.workload-token=postgres-workload-token"
})
class MediaPostgresIdempotencyIntegrationTest {

    private static final String ACCESS_TOKEN_PROOF =
            "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";
    private static final Path STORAGE_ROOT = Path.of(
            System.getProperty("java.io.tmpdir"), "lifeos-media-postgres-" + UUID.randomUUID());

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("lifeos_media")
            .withUsername("lifeos")
            .withPassword("test-only-postgres-password");

    @Autowired
    private MediaManagementService service;

    @Autowired
    private MediaAssetRepository assetRepository;

    @Autowired
    private MediaSessionRepository sessionRepository;

    @Autowired
    private MediaMutationIdempotencyRepository idempotencyRepository;

    @Autowired
    private MediaSecurityAuditEventRepository auditRepository;

    @MockitoBean
    private MediaAccessService accessService;

    private MediaSubject subject;

    @DynamicPropertySource
    static void configureDataSourceAndStorage(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
        registry.add("media.storage.local-root", () -> STORAGE_ROOT.toString());
    }

    @BeforeEach
    void setUp() {
        auditRepository.deleteAll();
        idempotencyRepository.deleteAll();
        assetRepository.deleteAll();
        sessionRepository.deleteAll();
        subject = new MediaSubject(UUID.randomUUID(), UUID.randomUUID(), "password", ACCESS_TOKEN_PROOF);
    }

    @Test
    void concurrentMatchingCreateCommandsConvergeOnOnePostgresAssetAndOneSnapshot() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<MediaIdempotencyResult<MediaAssetResponse>> first =
                    executor.submit(() -> createAfterStart(ready, start));
            Future<MediaIdempotencyResult<MediaAssetResponse>> second =
                    executor.submit(() -> createAfterStart(ready, start));

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            MediaIdempotencyResult<MediaAssetResponse> firstResult = first.get(20, TimeUnit.SECONDS);
            MediaIdempotencyResult<MediaAssetResponse> secondResult = second.get(20, TimeUnit.SECONDS);

            assertThat(firstResult.body()).isEqualTo(secondResult.body());
            assertThat(firstResult.replayed() || secondResult.replayed()).isTrue();
            assertThat(assetRepository.count()).isEqualTo(1L);
            assertThat(idempotencyRepository.count()).isEqualTo(1L);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private MediaIdempotencyResult<MediaAssetResponse> createAfterStart(CountDownLatch ready, CountDownLatch start)
            throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new AssertionError("concurrent media create did not receive its start signal");
        }
        return service.createAsset(subject, new CreateMediaAssetRequest("Postgres clip"), "postgres-media-create-key");
    }
}
