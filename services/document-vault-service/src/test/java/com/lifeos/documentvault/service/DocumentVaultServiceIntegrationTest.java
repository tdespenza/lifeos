package com.lifeos.documentvault.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lifeos.documentvault.audit.DocumentVaultSecurityAuditEventRepository;
import com.lifeos.documentvault.authorization.DocumentVaultAccessService;
import com.lifeos.documentvault.authorization.DocumentVaultSubject;
import com.lifeos.documentvault.domain.DocumentClassification;
import com.lifeos.documentvault.domain.DocumentMetadata;
import com.lifeos.documentvault.domain.DocumentSource;
import com.lifeos.documentvault.domain.VaultDocumentRepository;
import com.lifeos.documentvault.idempotency.DocumentCommandIdempotencyRepository;
import com.lifeos.documentvault.idempotency.DocumentCommandResult;
import com.lifeos.documentvault.idempotency.DocumentVersionConflictException;
import com.lifeos.documentvault.idempotency.DocumentIdempotencyConflictException;
import com.lifeos.documentvault.proof.DocumentProofOutboxEventRepository;
import com.lifeos.documentvault.proof.DocumentProofOutboxTransactions;
import com.lifeos.documentvault.proof.DocumentProofRequestRepository;
import com.lifeos.documentvault.proof.DocumentProofRequestState;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
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

/** H2 integration coverage for durable retries, ETags, bounded search, and owner scope. */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:document-vault-service-integration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=document-vault-integration-password",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.flyway.enabled=true",
    "spring.flyway.locations=classpath:db/migration-h2",
    "document-vault.idempotency-secret=integration-idempotency-secret",
    "document-vault.audit-client-fingerprint-secret=integration-audit-secret",
    "document-vault.proof-outbox.relay-enabled=false",
    "identity.workload-token=integration-workload-token"
})
class DocumentVaultServiceIntegrationTest {

    private static final String ACCESS_TOKEN_PROOF =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final Path STORAGE_ROOT = Path.of(
            System.getProperty("java.io.tmpdir"), "lifeos-document-vault-integration-" + UUID.randomUUID());

    @Autowired
    private DocumentVaultManagementService service;

    @Autowired
    private VaultDocumentRepository documentRepository;

    @Autowired
    private DocumentCommandIdempotencyRepository idempotencyRepository;

    @Autowired
    private DocumentVaultSecurityAuditEventRepository auditRepository;

    @Autowired
    private DocumentProofRequestRepository proofRequestRepository;

    @Autowired
    private DocumentProofOutboxEventRepository proofOutboxRepository;

    @Autowired
    private DocumentProofOutboxTransactions proofOutboxTransactions;

    @MockitoBean
    private DocumentVaultAccessService accessService;

    private DocumentVaultSubject subject;

    @DynamicPropertySource
    static void localStorage(DynamicPropertyRegistry registry) {
        registry.add("document-vault.storage.local-root", () -> STORAGE_ROOT.toString());
    }

    @BeforeEach
    void setUp() {
        auditRepository.deleteAll();
        proofOutboxRepository.deleteAll();
        proofRequestRepository.deleteAll();
        idempotencyRepository.deleteAll();
        documentRepository.deleteAll();
        subject = subject();
    }

    @Test
    void matchingUploadReplayReturnsOriginalSnapshotAfterMetadataChanges() {
        DocumentMetadata initial = metadata("Travel receipt", DocumentSource.UPLOAD);
        DocumentCommandResult created = upload(subject, initial, "document-upload-replay");

        DocumentCommandResult updated = service.updateMetadata(
                subject,
                created.document().id(),
                created.document().version(),
                metadata("Travel receipt categorized", DocumentSource.IMPORT),
                "document-metadata-update");
        DocumentCommandResult replay = upload(subject, initial, "document-upload-replay");

        assertThat(created.replayed()).isFalse();
        assertThat(updated.document().version()).isEqualTo(1L);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.document()).isEqualTo(created.document());
        assertThat(documentRepository.count()).isEqualTo(1L);
        assertThat(idempotencyRepository.count()).isEqualTo(2L);
    }

    @Test
    void rejectsStaleMetadataVersionWithoutLeavingAPendingReservation() {
        DocumentCommandResult created = upload(subject, metadata("Bank statement", DocumentSource.UPLOAD), "document-create");
        service.updateMetadata(
                subject,
                created.document().id(),
                0L,
                metadata("Bank statement 2026", DocumentSource.SCANNER),
                "document-update-current");

        assertThatThrownBy(() -> service.updateMetadata(
                        subject,
                        created.document().id(),
                        0L,
                        metadata("Bank statement stale", DocumentSource.IMPORT),
                        "document-update-stale"))
                .isInstanceOf(DocumentVersionConflictException.class);

        assertThat(idempotencyRepository.count()).isEqualTo(2L);
    }

    @Test
    void searchesOnlyTheAuthenticatedOwnersDocumentsWithABoundedPage() {
        upload(subject, metadata("Travel itinerary", DocumentSource.UPLOAD), "owner-travel-one");
        upload(subject, metadata("Travel receipt", DocumentSource.UPLOAD), "owner-travel-two");
        DocumentVaultSubject other = subject();
        DocumentCommandResult otherDocument = upload(other, metadata("Travel private", DocumentSource.UPLOAD), "other-travel");

        DocumentSearchPage page = service.search(subject, new DocumentSearchQuery("travel", 0, 1));

        assertThat(page.results()).hasSize(1);
        assertThat(page.hasNext()).isTrue();
        assertThat(page.results()).extracting(DocumentSearchResult::id).doesNotContain(otherDocument.document().id());
        assertThat(page.results().getFirst().relevance()).isGreaterThanOrEqualTo(60);
    }

    @Test
    void proofRequestIsDurableExactlyOnceAndReplaysTheOriginalSnapshot() {
        DocumentCommandResult created = upload(subject, metadata("Proof candidate", DocumentSource.UPLOAD), "proof-document");

        var first = service.requestProof(subject, created.document().id(), "proof-request-key");
        var replay = service.requestProof(subject, created.document().id(), "proof-request-key");

        assertThat(first.replayed()).isFalse();
        assertThat(first.state()).isEqualTo(DocumentProofRequestState.REQUESTED);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.requestId()).isEqualTo(first.requestId());
        assertThat(proofRequestRepository.count()).isEqualTo(1L);
        assertThat(proofOutboxRepository.count()).isEqualTo(1L);
        assertThat(proofOutboxRepository.findAll().getFirst().getEventType())
                .isEqualTo("com.lifeos.document.proof.requested.v1");
        assertThat(proofOutboxRepository.findAll().getFirst().getPayloadJson())
                .contains("com.lifeos.document.proof.requested.v1")
                .contains(first.requestId().toString());

        var claimed = proofOutboxTransactions.claimBatch();
        assertThat(claimed).hasSize(1);
        assertThat(claimed.getFirst().topic()).isEqualTo("lifeos.document.proof.requested.v1");
        assertThat(claimed.getFirst().partitionKey()).startsWith("document/");
        assertThat(proofOutboxTransactions.rescheduleOrDeadLetter(claimed.getFirst())).isTrue();

        DocumentCommandResult secondDocument = upload(
                subject, metadata("Second proof candidate", DocumentSource.UPLOAD), "proof-document-two");
        assertThatThrownBy(() -> service.requestProof(subject, secondDocument.document().id(), "proof-request-key"))
                .isInstanceOf(DocumentIdempotencyConflictException.class);
        assertThat(proofRequestRepository.count()).isEqualTo(1L);
    }

    private DocumentCommandResult upload(DocumentVaultSubject owner, DocumentMetadata metadata, String idempotencyKey) {
        return service.upload(
                owner,
                new ByteArrayInputStream("%PDF-1.7\nprotected document".getBytes(StandardCharsets.US_ASCII)),
                "application/pdf",
                metadata,
                idempotencyKey);
    }

    private static DocumentMetadata metadata(String title, DocumentSource source) {
        return new DocumentMetadata(
                title,
                java.util.List.of("finance", "travel"),
                Instant.parse("2026-08-18T12:00:00Z"),
                source,
                DocumentClassification.PRIVATE);
    }

    private static DocumentVaultSubject subject() {
        return new DocumentVaultSubject(UUID.randomUUID(), UUID.randomUUID(), "password", ACCESS_TOKEN_PROOF);
    }
}
