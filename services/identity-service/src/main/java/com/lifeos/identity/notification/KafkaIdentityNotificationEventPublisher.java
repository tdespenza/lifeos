package com.lifeos.identity.notification;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/** Bounded synchronous Kafka acknowledgement adapter for Identity recovery notifications. */
@Component
public class KafkaIdentityNotificationEventPublisher implements IdentityNotificationEventPublisher {

    private static final Duration PUBLISH_TIMEOUT = Duration.ofSeconds(5);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public KafkaIdentityNotificationEventPublisher(
            KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publish(ClaimedIdentityNotificationOutboxEvent event) {
        try {
            ProducerRecord<String, String> record = new ProducerRecord<>(
                    event.topic(), event.partitionKey(), event.payloadJson());
            Map<String, String> headers = objectMapper.readValue(
                    event.headersJson(), new TypeReference<>() {
                    });
            headers.forEach((name, value) -> record.headers().add(
                    name, value.getBytes(StandardCharsets.UTF_8)));
            kafkaTemplate.send(record).get(PUBLISH_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IdentityNotificationPublishException(exception);
        } catch (Exception exception) {
            throw new IdentityNotificationPublishException(exception);
        }
    }
}
