package com.lifeos.assistant.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable audit metadata for one assistant decision.
 *
 * <p>Prompt text, output text, bearer tokens, account profile data, and source addresses are not
 * columns. Correlation and address values are keyed digests where they need durable linkage.
 */
@Entity
@Table(name = "assistant_request_audit_event")
public class AssistantRequestAuditEvent {

    @Id
    private UUID id;

    @Column(name = "conversation_id", updatable = false)
    private UUID conversationId;

    @Column(name = "owner_account_id", updatable = false)
    private UUID ownerAccountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "request_kind", nullable = false, updatable = false, length = 32)
    private AssistantAuditRequestKind requestKind;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, updatable = false, length = 32)
    private AssistantAuditOutcome outcome;

    @Column(name = "prompt_template_id", nullable = false, updatable = false, length = 64)
    private String promptTemplateId;

    @Column(name = "input_fingerprint", updatable = false, length = 64)
    private String inputFingerprint;

    @Column(name = "input_characters", nullable = false, updatable = false)
    private int inputCharacters;

    @Column(name = "estimated_input_tokens", nullable = false, updatable = false)
    private int estimatedInputTokens;

    @Column(name = "requested_output_tokens", nullable = false, updatable = false)
    private int requestedOutputTokens;

    @Column(name = "retrieved_context_ids", nullable = false, updatable = false, length = 512)
    private String retrievedContextIds;

    @Column(name = "safety_flags", nullable = false, updatable = false, length = 256)
    private String safetyFlags;

    @Column(name = "provider_id", nullable = false, updatable = false, length = 64)
    private String providerId;

    @Column(name = "model_name", nullable = false, updatable = false, length = 128)
    private String modelName;

    @Column(name = "output_summary", nullable = false, updatable = false, length = 64)
    private String outputSummary;

    @Column(name = "output_fingerprint", updatable = false, length = 64)
    private String outputFingerprint;

    @Column(name = "output_characters", nullable = false, updatable = false)
    private int outputCharacters;

    @Column(name = "confidence_score", updatable = false, precision = 5, scale = 4)
    private BigDecimal confidenceScore;

    @Column(name = "tool_operation", nullable = false, updatable = false, length = 64)
    private String toolOperation;

    @Column(name = "tool_execution_state", nullable = false, updatable = false, length = 32)
    private String toolExecutionState;

    @Column(name = "latency_millis", nullable = false, updatable = false)
    private long latencyMillis;

    @Column(name = "correlation_id", nullable = false, updatable = false, length = 128)
    private String correlationId;

    @Column(name = "client_fingerprint", nullable = false, updatable = false, length = 64)
    private String clientFingerprint;

    /**
     * Canonical SHA-256 commitment over the redacted event fields. This is intentionally
     * independent of the database id and timestamp so the commitment can be reproduced by an
     * eventual outbox/anchoring workflow without exposing prompt or completion content.
     */
    @Column(name = "audit_hash_sha256", updatable = false, length = 64)
    private String auditHashSha256;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    protected AssistantRequestAuditEvent() {
        // required by JPA
    }

    AssistantRequestAuditEvent(
            AssistantAuditRecord record,
            String inputFingerprint,
            String outputFingerprint,
            String clientFingerprint,
            String auditHashSha256) {
        id = UUID.randomUUID();
        conversationId = record.conversationId();
        ownerAccountId = record.ownerAccountId();
        requestKind = Objects.requireNonNull(record.requestKind(), "requestKind must not be null");
        outcome = Objects.requireNonNull(record.outcome(), "outcome must not be null");
        promptTemplateId = bounded(record.promptTemplateId(), 64, "promptTemplateId");
        this.inputFingerprint = optionalDigest(inputFingerprint, "inputFingerprint");
        inputCharacters = nonNegative(record.inputCharacters(), "inputCharacters");
        estimatedInputTokens = nonNegative(record.estimatedInputTokens(), "estimatedInputTokens");
        requestedOutputTokens = nonNegative(record.requestedOutputTokens(), "requestedOutputTokens");
        retrievedContextIds = bounded(record.retrievedContextIds(), 512, "retrievedContextIds");
        safetyFlags = bounded(record.safetyFlags(), 256, "safetyFlags");
        providerId = bounded(record.providerId(), 64, "providerId");
        modelName = bounded(record.modelName(), 128, "modelName");
        outputSummary = bounded(record.outputSummary(), 64, "outputSummary");
        this.outputFingerprint = optionalDigest(outputFingerprint, "outputFingerprint");
        outputCharacters = nonNegative(record.outputCharacters(), "outputCharacters");
        confidenceScore = confidence(record.confidenceScore());
        toolOperation = bounded(record.toolOperation(), 64, "toolOperation");
        toolExecutionState = bounded(record.toolExecutionState(), 32, "toolExecutionState");
        latencyMillis = nonNegative(record.latencyMillis(), "latencyMillis");
        correlationId = bounded(record.correlationId(), 128, "correlationId");
        this.clientFingerprint = digest(clientFingerprint, "clientFingerprint");
        this.auditHashSha256 = optionalDigest(auditHashSha256, "auditHashSha256");
        occurredAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getConversationId() {
        return conversationId;
    }

    public UUID getOwnerAccountId() {
        return ownerAccountId;
    }

    public AssistantAuditRequestKind getRequestKind() {
        return requestKind;
    }

    public AssistantAuditOutcome getOutcome() {
        return outcome;
    }

    public String getSafetyFlags() {
        return safetyFlags;
    }

    public String getInputFingerprint() {
        return inputFingerprint;
    }

    public String getOutputFingerprint() {
        return outputFingerprint;
    }

    public String getOutputSummary() {
        return outputSummary;
    }

    public String getClientFingerprint() {
        return clientFingerprint;
    }

    public String getAuditHashSha256() {
        return auditHashSha256;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    private static String bounded(String value, int maximumLength, String name) {
        if (value == null || value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(name + " must be bounded and non-blank");
        }
        return value;
    }

    private static int nonNegative(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return value;
    }

    private static long nonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return value;
    }

    private static String optionalDigest(String value, String name) {
        if (value == null) {
            return null;
        }
        return digest(value, name);
    }

    private static String digest(String value, String name) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a SHA-256 digest");
        }
        return value;
    }

    private static BigDecimal confidence(BigDecimal value) {
        if (value == null) {
            return null;
        }
        if (value.scale() > 4
                || value.compareTo(BigDecimal.ZERO) < 0
                || value.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("confidenceScore must be between zero and one");
        }
        return value;
    }
}
