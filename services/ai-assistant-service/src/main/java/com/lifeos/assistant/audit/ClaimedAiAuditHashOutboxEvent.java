package com.lifeos.assistant.audit;

import java.util.UUID;

public record ClaimedAiAuditHashOutboxEvent(
        UUID id,
        UUID auditEventId,
        UUID leaseToken,
        String eventType,
        String topic,
        String partitionKey,
        String payloadJson,
        int attemptCount) {
}
