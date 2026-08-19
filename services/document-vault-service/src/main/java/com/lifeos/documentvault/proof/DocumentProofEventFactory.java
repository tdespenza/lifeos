package com.lifeos.documentvault.proof;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifeos.documentvault.observability.RequestContext;
import com.lifeos.events.v1.CloudEventV1;
import com.lifeos.events.v1.DocumentProofRequestedV1;
import com.lifeos.events.v1.EventContract;
import java.net.URI;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Serializes the immutable proof command before the database transaction commits. */
@Component
public class DocumentProofEventFactory {

    private static final URI SOURCE = URI.create("urn:lifeos:document-vault-service");
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public DocumentProofEventFactory(ObjectMapper objectMapper, Clock clock) {
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public String createPayload(DocumentProofRequest request) {
        UUID correlationId = correlationId(request.getId());
        DocumentProofRequestedV1 command = new DocumentProofRequestedV1(
                request.getId(),
                request.getDocumentId(),
                request.getOwnerAccountId(),
                request.getTenantId(),
                request.getDocumentVersion(),
                request.getChecksumSha256());
        CloudEventV1<DocumentProofRequestedV1> event = new CloudEventV1<>(
                request.getId(),
                EventContract.CLOUD_EVENTS_SPEC_VERSION,
                SOURCE,
                EventContract.DOCUMENT_PROOF_REQUESTED_V1_TYPE,
                "document/" + request.getDocumentId(),
                clock.instant(),
                "application/json",
                correlationId,
                command);
        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception exception) {
            throw new DocumentProofSerializationException(exception);
        }
    }

    private static UUID correlationId(UUID fallback) {
        if (RequestContext.CORRELATION_ID.isBound()) {
            try {
                return UUID.fromString(RequestContext.CORRELATION_ID.get());
            } catch (IllegalArgumentException ignored) {
                // CorrelationIdFilter normally guarantees a UUID; use the durable request ID if called off-request.
            }
        }
        return fallback;
    }
}
