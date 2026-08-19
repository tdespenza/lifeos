package com.lifeos.trustledger.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifeos.events.v1.CloudEventV1;
import com.lifeos.events.v1.DocumentProofRequestedV1;
import com.lifeos.events.v1.EventContract;
import com.lifeos.trustledger.proof.TrustDocumentProofRequest;
import com.lifeos.trustledger.proof.TrustDocumentProofRequestRepository;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Validates and durably deduplicates privacy-minimized proof commands. */
@Service
public class TrustDocumentProofIngressService {

    private static final URI DOCUMENT_VAULT_SOURCE = URI.create("urn:lifeos:document-vault-service");

    private final ObjectMapper objectMapper;
    private final TrustDocumentProofRequestRepository repository;
    private final Clock clock;

    public TrustDocumentProofIngressService(
            ObjectMapper objectMapper, TrustDocumentProofRequestRepository repository, Clock clock) {
        this.objectMapper = objectMapper;
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * Accepts one CloudEvent and returns false for an already committed event id. Any malformed
     * event throws so Kafka's bounded error handler can route it to its configured DLT.
     */
    @Transactional(timeout = 5)
    public boolean accept(String payload) {
        CloudEventV1<DocumentProofRequestedV1> event = parse(payload);
        if (repository.existsById(event.id())) {
            return false;
        }
        DocumentProofRequestedV1 command = event.data();
        if (!event.id().equals(command.requestId())) {
            throw new IllegalArgumentException("proof event id must equal request id");
        }
        try {
            repository.saveAndFlush(new TrustDocumentProofRequest(
                    command.requestId(),
                    command.documentId(),
                    command.ownerAccountId(),
                    command.tenantId(),
                    command.documentVersion(),
                    command.checksumSha256(),
                    clock.instant()));
            return true;
        } catch (DataIntegrityViolationException race) {
            return false;
        }
    }

    private CloudEventV1<DocumentProofRequestedV1> parse(String payload) {
        try {
            if (payload == null || payload.length() > 1_000_000) {
                throw new IllegalArgumentException("proof event payload is missing or oversized");
            }
            JsonNode root = objectMapper.readTree(payload);
            DocumentProofRequestedV1 command = objectMapper.treeToValue(
                    root.path("data"), DocumentProofRequestedV1.class);
            CloudEventV1<DocumentProofRequestedV1> event = new CloudEventV1<>(
                    UUID.fromString(root.path("id").asText()),
                    root.path("specversion").asText(),
                    URI.create(root.path("source").asText()),
                    root.path("type").asText(),
                    root.path("subject").asText(),
                    Instant.parse(root.path("time").asText()),
                    root.path("datacontenttype").asText(),
                    UUID.fromString(root.path("correlationId").asText()),
                    command);
            if (!EventContract.DOCUMENT_PROOF_REQUESTED_V1_TYPE.equals(event.type())
                    || !DOCUMENT_VAULT_SOURCE.equals(event.source())) {
                throw new IllegalArgumentException("unexpected proof event type or source");
            }
            return event;
        } catch (Exception exception) {
            if (exception instanceof IllegalArgumentException illegal) {
                throw illegal;
            }
            throw new IllegalArgumentException("invalid document proof event", exception);
        }
    }
}
