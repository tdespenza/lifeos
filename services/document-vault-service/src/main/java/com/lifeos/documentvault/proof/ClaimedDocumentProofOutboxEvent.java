package com.lifeos.documentvault.proof;

import java.util.UUID;

public record ClaimedDocumentProofOutboxEvent(
        UUID id,
        UUID proofRequestId,
        UUID leaseToken,
        String eventType,
        String topic,
        String partitionKey,
        String payloadJson,
        int attemptCount) {
}
