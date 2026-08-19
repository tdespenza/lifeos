package com.lifeos.documentvault.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import com.lifeos.documentvault.audit.DocumentVaultSecurityAuditEventRepository;
import com.lifeos.documentvault.authorization.DocumentVaultAccessService;
import com.lifeos.documentvault.authorization.DocumentVaultSubject;
import com.lifeos.documentvault.domain.DocumentClassification;
import com.lifeos.documentvault.domain.DocumentMetadata;
import com.lifeos.documentvault.domain.DocumentSource;
import com.lifeos.documentvault.domain.VaultDocumentRepository;
import com.lifeos.documentvault.service.DocumentVaultManagementService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
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

/** PostgreSQL concurrency coverage for one durable upload reservation and exact replay snapshot. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true",
    "document-vault.idempotency-secret=postgres-idempotency-secret",
    "document-vault.audit-client-fingerprint-secret=postgres-audit-secret",
    "document-vault.proof-outbox.relay-enabled=false",
    "identity.workload-token=postgres-workload-token"
})
class DocumentVaultPostgresIntegrationTest {

    private static final String ACCESS_TOKEN_PROOF =
            "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";
    private static final Path STORAGE_ROOT = Path.of(
            System.getProperty("java.io.tmpdir"), "lifeos-document-vault-postgres-" + UUID.randomUUID());

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("lifeos_document_vault")
            .withUsername("lifeos")
            .withPassword("test-only-postgres-password");

    @Autowired
    private DocumentVaultManagementService service;

    @Autowired
    private VaultDocumentRepository documentRepository;

    @Autowired
    private DocumentCommandIdempotencyRepository idempotencyRepository;

    @Autowired
    private DocumentVaultSecurityAuditEventRepository auditRepository;

    @MockitoBean
    private DocumentVaultAccessService accessService;

    private DocumentVaultSubject subject;

    @DynamicPropertySource
    static void configureDataSourceAndStorage(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
        registry.add("document-vault.storage.local-root", () -> STORAGE_ROOT.toString());
    }

    @BeforeEach
    void setUp() {
        auditRepository.deleteAll();
        idempotencyRepository.deleteAll();
        documentRepository.deleteAll();
        subject = new DocumentVaultSubject(UUID.randomUUID(), UUID.randomUUID(), "password", ACCESS_TOKEN_PROOF);
    }

    @Test
    void concurrentMatchingUploadsConvergeOnOnePostgresDocumentAndOneSnapshot() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<DocumentCommandResult> first = executor.submit(() -> uploadAfterStart(ready, start));
            Future<DocumentCommandResult> second = executor.submit(() -> uploadAfterStart(ready, start));

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            DocumentCommandResult firstResult = first.get(20, TimeUnit.SECONDS);
            DocumentCommandResult secondResult = second.get(20, TimeUnit.SECONDS);

            assertThat(secondResult.document()).isEqualTo(firstResult.document());
            assertThat(firstResult.replayed() || secondResult.replayed()).isTrue();
            assertThat(documentRepository.count()).isEqualTo(1L);
            assertThat(idempotencyRepository.count()).isEqualTo(1L);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private DocumentCommandResult uploadAfterStart(CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new AssertionError("concurrent document upload did not receive its start signal");
        }
        return service.upload(
                subject,
                new ByteArrayInputStream("%PDF-1.7\npostgres document".getBytes(StandardCharsets.US_ASCII)),
                "application/pdf",
                new DocumentMetadata(
                        "Postgres receipt",
                        java.util.List.of("finance"),
                        null,
                        DocumentSource.UPLOAD,
                        DocumentClassification.PRIVATE),
                "postgres-concurrent-document-upload");
    }
}
