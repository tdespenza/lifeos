package com.lifeos.trustledger.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifeos.events.v1.AiAuditHashRequestedV1;
import com.lifeos.events.v1.CloudEventV1;
import com.lifeos.events.v1.EventContract;
import com.lifeos.trustledger.proof.TrustAiAuditHashRequestRepository;
import com.lifeos.trustledger.proof.TrustAiAuditHashState;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** H2-backed coverage for durable AI commitment projection and conflict-safe redelivery. */
@SpringBootTest
class TrustAiAuditHashIngressIntegrationTest {

    private static final UUID AUDIT_ID = UUID.fromString("00000000-0000-4000-8000-000000000011");
    private static final UUID OWNER_ID = UUID.fromString("00000000-0000-4000-8000-000000000012");
    private static final String HASH = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TrustAiAuditHashIngressService ingress;

    @Autowired
    private TrustAiAuditHashRequestRepository repository;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
    }

    @Test
    void persistsPendingCommitmentAndTreatsExactRedeliveryAsReplay() throws Exception {
        String payload = objectMapper.writeValueAsString(event(AUDIT_ID, OWNER_ID, HASH));

        assertThat(ingress.accept(payload)).isTrue();
        assertThat(ingress.accept(payload)).isFalse();
        assertThat(repository.findById(AUDIT_ID))
                .get()
                .satisfies(request -> {
                    assertThat(request.getOwnerAccountId()).isEqualTo(OWNER_ID);
                    assertThat(request.getAuditHashSha256()).isEqualTo(HASH);
                    assertThat(request.getState()).isEqualTo(TrustAiAuditHashState.PENDING_EXTERNAL_ANCHOR);
                });
    }

    @Test
    void rejectsUntrustedSourceAndConflictingCommitment() throws Exception {
        CloudEventV1<AiAuditHashRequestedV1> untrusted = new CloudEventV1<>(
                AUDIT_ID,
                EventContract.CLOUD_EVENTS_SPEC_VERSION,
                URI.create("urn:lifeos:untrusted-service"),
                EventContract.AI_AUDIT_HASH_REQUESTED_V1_TYPE,
                "assistant-audit/" + AUDIT_ID,
                Instant.parse("2026-08-18T00:00:00Z"),
                "application/json",
                AUDIT_ID,
                new AiAuditHashRequestedV1(AUDIT_ID, OWNER_ID, null, HASH));
        assertThatThrownBy(() -> ingress.accept(objectMapper.writeValueAsString(untrusted)))
                .isInstanceOf(IllegalArgumentException.class);

        ingress.accept(objectMapper.writeValueAsString(event(AUDIT_ID, OWNER_ID, HASH)));
        String conflicting = objectMapper.writeValueAsString(event(
                AUDIT_ID,
                OWNER_ID,
                "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"));
        assertThatThrownBy(() -> ingress.accept(conflicting))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static CloudEventV1<AiAuditHashRequestedV1> event(UUID auditId, UUID ownerId, String hash) {
        return new CloudEventV1<>(
                auditId,
                EventContract.CLOUD_EVENTS_SPEC_VERSION,
                URI.create("urn:lifeos:ai-assistant-service"),
                EventContract.AI_AUDIT_HASH_REQUESTED_V1_TYPE,
                "assistant-audit/" + auditId,
                Instant.parse("2026-08-18T00:00:00Z"),
                "application/json",
                auditId,
                new AiAuditHashRequestedV1(auditId, ownerId, null, hash));
    }
}
