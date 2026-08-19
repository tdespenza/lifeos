package com.lifeos.assistant.audit;

public interface AiAuditHashOutboxPublisher {
    void publish(ClaimedAiAuditHashOutboxEvent event);
}
