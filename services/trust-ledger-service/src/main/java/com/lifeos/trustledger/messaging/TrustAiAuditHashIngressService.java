package com.lifeos.trustledger.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifeos.events.v1.AiAuditHashRequestedV1;
import com.lifeos.events.v1.CloudEventV1;
import com.lifeos.events.v1.EventContract;
import com.lifeos.trustledger.proof.TrustAiAuditHashRequest;
import com.lifeos.trustledger.proof.TrustAiAuditHashRequestRepository;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Validates and durably deduplicates hash-only AI audit commands. */
@Service
public class TrustAiAuditHashIngressService {

    private static final URI AI_ASSISTANT_SOURCE = URI.create("urn:lifeos:ai-assistant-service");

    private final ObjectMapper objectMapper;
    private final TrustAiAuditHashRequestRepository repository;
    private final Clock clock;

    public TrustAiAuditHashIngressService(
            ObjectMapper objectMapper, TrustAiAuditHashRequestRepository repository, Clock clock) {
        this.objectMapper = objectMapper;
        this.repository = repository;
        this.clock = clock;
    }

    /** Returns false for an exact redelivery; conflicting event ids fail closed. */
    @Transactional(timeout = 5)
    public boolean accept(String payload) {
        CloudEventV1<AiAuditHashRequestedV1> event = parse(payload);
        AiAuditHashRequestedV1 command = event.data();
        if (!event.id().equals(command.auditEventId())) {
            throw new IllegalArgumentException("audit event id must equal CloudEvent id");
        }
        var existing = repository.findById(command.auditEventId());
        if (existing.isPresent()) {
            if (!existing.get().getAuditHashSha256().equals(command.auditHashSha256())) {
                throw new IllegalArgumentException("audit event id has a conflicting commitment");
            }
            return false;
        }
        try {
            repository.saveAndFlush(new TrustAiAuditHashRequest(
                    command.auditEventId(),
                    command.ownerAccountId(),
                    command.conversationId(),
                    command.auditHashSha256(),
                    clock.instant()));
            return true;
        } catch (DataIntegrityViolationException race) {
            return false;
        }
    }

    private CloudEventV1<AiAuditHashRequestedV1> parse(String payload) {
        try {
            if (payload == null || payload.length() > 1_000_000) {
                throw new IllegalArgumentException("AI audit event payload is missing or oversized");
            }
            JsonNode root = objectMapper.readTree(payload);
            AiAuditHashRequestedV1 command = objectMapper.treeToValue(
                    root.path("data"), AiAuditHashRequestedV1.class);
            CloudEventV1<AiAuditHashRequestedV1> event = new CloudEventV1<>(
                    UUID.fromString(root.path("id").asText()),
                    root.path("specversion").asText(),
                    URI.create(root.path("source").asText()),
                    root.path("type").asText(),
                    root.path("subject").asText(),
                    Instant.parse(root.path("time").asText()),
                    root.path("datacontenttype").asText(),
                    UUID.fromString(root.path("correlationId").asText()),
                    command);
            if (!EventContract.AI_AUDIT_HASH_REQUESTED_V1_TYPE.equals(event.type())
                    || !AI_ASSISTANT_SOURCE.equals(event.source())
                    || !event.subject().equals("assistant-audit/" + event.id())) {
                throw new IllegalArgumentException("unexpected AI audit event type, source, or subject");
            }
            return event;
        } catch (Exception exception) {
            if (exception instanceof IllegalArgumentException illegal) {
                throw illegal;
            }
            throw new IllegalArgumentException("invalid AI audit event", exception);
        }
    }
}
