package com.lifeos.trustledger.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifeos.events.v1.CloudEventV1;
import com.lifeos.events.v1.DocumentProofRequestedV1;
import com.lifeos.events.v1.EventContract;
import com.lifeos.trustledger.proof.TrustDocumentProofRequestRepository;
import com.lifeos.trustledger.proof.TrustDocumentProofState;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** H2-backed proof command consumer coverage for durable idempotency and payload validation. */
@SpringBootTest
class TrustDocumentProofIngressIntegrationTest {

    private static final UUID REQUEST_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID DOCUMENT_ID = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final UUID OWNER_ID = UUID.fromString("00000000-0000-4000-8000-000000000003");
    private static final String CHECKSUM = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TrustDocumentProofIngressService ingress;

    @Autowired
    private TrustDocumentProofRequestRepository repository;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
    }

    @Test
    void persistsOnePendingProjectionAndTreatsRedeliveryAsAReplay() throws Exception {
        String payload = payload(REQUEST_ID, DOCUMENT_ID, OWNER_ID, CHECKSUM);

        assertThat(ingress.accept(payload)).isTrue();
        assertThat(ingress.accept(payload)).isFalse();
        assertThat(repository.findById(REQUEST_ID))
                .get()
                .satisfies(request -> {
                    assertThat(request.getDocumentId()).isEqualTo(DOCUMENT_ID);
                    assertThat(request.getOwnerAccountId()).isEqualTo(OWNER_ID);
                    assertThat(request.getState()).isEqualTo(TrustDocumentProofState.PENDING_EXTERNAL_ANCHOR);
                    assertThat(request.getChecksumSha256()).isEqualTo(CHECKSUM);
                });
    }

    @Test
    void rejectsWrongSourceAndMismatchedEventIdentityBeforePersistence() throws Exception {
        CloudEventV1<DocumentProofRequestedV1> event = event(
                REQUEST_ID,
                REQUEST_ID,
                DOCUMENT_ID,
                OWNER_ID,
                CHECKSUM,
                URI.create("urn:lifeos:untrusted-service"));
        assertThatThrownBy(() -> ingress.accept(objectMapper.writeValueAsString(event)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(repository.count()).isZero();

        CloudEventV1<DocumentProofRequestedV1> mismatched = event(
                UUID.fromString("00000000-0000-4000-8000-000000000004"),
                REQUEST_ID,
                DOCUMENT_ID,
                OWNER_ID,
                CHECKSUM,
                URI.create("urn:lifeos:document-vault-service"));
        assertThatThrownBy(() -> ingress.accept(objectMapper.writeValueAsString(mismatched)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(repository.count()).isZero();
    }

    private String payload(UUID requestId, UUID documentId, UUID ownerId, String checksum) throws Exception {
        return objectMapper.writeValueAsString(event(
                requestId,
                requestId,
                documentId,
                ownerId,
                checksum,
                URI.create("urn:lifeos:document-vault-service")));
    }

    private static CloudEventV1<DocumentProofRequestedV1> event(
            UUID eventId, UUID requestId, UUID documentId, UUID ownerId, String checksum, URI source) {
        return new CloudEventV1<>(
                eventId,
                EventContract.CLOUD_EVENTS_SPEC_VERSION,
                source,
                EventContract.DOCUMENT_PROOF_REQUESTED_V1_TYPE,
                "document/" + documentId,
                Instant.parse("2026-08-18T00:00:00Z"),
                "application/json",
                requestId,
                new DocumentProofRequestedV1(requestId, documentId, ownerId, "personal", 1, checksum));
    }
}
