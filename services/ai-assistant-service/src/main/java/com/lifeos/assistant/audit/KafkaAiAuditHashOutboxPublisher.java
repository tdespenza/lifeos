package com.lifeos.assistant.audit;

import com.lifeos.assistant.config.AiAuditOutboxProperties;
import java.util.concurrent.TimeUnit;
import org.springframework.kafka.core.KafkaTemplate;

/** Bounded Kafka publisher; enabled only when the audit relay is explicitly enabled. */
public class KafkaAiAuditHashOutboxPublisher implements AiAuditHashOutboxPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final AiAuditOutboxProperties properties;

    public KafkaAiAuditHashOutboxPublisher(
            KafkaTemplate<String, String> kafkaTemplate, AiAuditOutboxProperties properties) {
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
    }

    @Override
    public void publish(ClaimedAiAuditHashOutboxEvent event) {
        try {
            kafkaTemplate.send(event.topic(), event.partitionKey(), event.payloadJson())
                    .get(properties.getPublishTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception exception) {
            throw new IllegalStateException("AI audit outbox publish failed", exception);
        }
    }
}
