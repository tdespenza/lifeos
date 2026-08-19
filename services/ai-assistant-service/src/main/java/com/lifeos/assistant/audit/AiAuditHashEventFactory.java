package com.lifeos.assistant.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifeos.assistant.observability.RequestContext;
import com.lifeos.events.v1.AiAuditHashRequestedV1;
import com.lifeos.events.v1.CloudEventV1;
import com.lifeos.events.v1.EventContract;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Serializes only the durable AI audit commitment into a versioned CloudEvents envelope. */
@Component
public class AiAuditHashEventFactory {

    private static final URI SOURCE = URI.create("urn:lifeos:ai-assistant-service");

    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AiAuditHashEventFactory(ObjectMapper objectMapper, Clock clock) {
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public String createPayload(AssistantRequestAuditEvent auditEvent) {
        Instant now = clock.instant();
        AiAuditHashRequestedV1 command = new AiAuditHashRequestedV1(
                auditEvent.getId(),
                auditEvent.getOwnerAccountId(),
                auditEvent.getConversationId(),
                auditEvent.getAuditHashSha256());
        CloudEventV1<AiAuditHashRequestedV1> event = new CloudEventV1<>(
                auditEvent.getId(),
                EventContract.CLOUD_EVENTS_SPEC_VERSION,
                SOURCE,
                EventContract.AI_AUDIT_HASH_REQUESTED_V1_TYPE,
                "assistant-audit/" + auditEvent.getId(),
                now,
                "application/json",
                correlationId(auditEvent.getId()),
                command);
        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception exception) {
            throw new AssistantAuditUnavailableException(exception);
        }
    }

    private static UUID correlationId(UUID fallback) {
        if (RequestContext.CORRELATION_ID.isBound()) {
            try {
                return UUID.fromString(RequestContext.CORRELATION_ID.get());
            } catch (IllegalArgumentException ignored) {
                // CorrelationIdFilter normally guarantees a UUID; use the immutable audit id off-request.
            }
        }
        return fallback;
    }
}
