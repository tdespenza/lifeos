package com.lifeos.assistant.audit;

import java.math.BigDecimal;
import java.util.UUID;

/** Safe input to durable audit persistence; raw prompt and output content are deliberately absent. */
public record AssistantAuditRecord(
        UUID conversationId,
        UUID ownerAccountId,
        AssistantAuditRequestKind requestKind,
        AssistantAuditOutcome outcome,
        String promptTemplateId,
        String inputForFingerprintOnly,
        int inputCharacters,
        int estimatedInputTokens,
        int requestedOutputTokens,
        String retrievedContextIds,
        String safetyFlags,
        String providerId,
        String modelName,
        String outputSummary,
        String outputForFingerprintOnly,
        int outputCharacters,
        BigDecimal confidenceScore,
        String toolOperation,
        String toolExecutionState,
        long latencyMillis,
        String correlationId) {

    @Override
    public String toString() {
        return "AssistantAuditRecord[redacted]";
    }
}
