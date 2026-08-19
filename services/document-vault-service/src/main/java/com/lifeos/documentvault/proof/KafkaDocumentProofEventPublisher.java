package com.lifeos.documentvault.proof;

import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/** Synchronous, bounded broker acknowledgement adapter used only outside database transactions. */
@Component
@ConditionalOnProperty(value = "document-vault.proof-outbox.relay-enabled", havingValue = "true")
public class KafkaDocumentProofEventPublisher implements DocumentProofEventPublisher {

    private static final Duration PUBLISH_TIMEOUT = Duration.ofSeconds(5);
    private final KafkaTemplate<String, String> kafkaTemplate;

    public KafkaDocumentProofEventPublisher(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void publish(ClaimedDocumentProofOutboxEvent event) {
        try {
            ProducerRecord<String, String> record = new ProducerRecord<>(
                    event.topic(), event.partitionKey(), event.payloadJson());
            record.headers().add("ce_id", event.proofRequestId().toString().getBytes(StandardCharsets.UTF_8));
            record.headers().add("ce_type", event.eventType().getBytes(StandardCharsets.UTF_8));
            kafkaTemplate.send(record)
                    .get(PUBLISH_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new DocumentProofPublishException(exception);
        } catch (Exception exception) {
            throw new DocumentProofPublishException(exception);
        }
    }
}
