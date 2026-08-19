package com.lifeos.notification.messaging;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

/** Kafka default relay adapter from ADR-016. The partition key is recipient/aggregate scoped. */
@Component
public class KafkaEventBusPublisher implements EventBusPublisher {

    private static final Duration ACK_TIMEOUT = Duration.ofSeconds(10);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public KafkaEventBusPublisher(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publish(ClaimedOutboxEvent event) {
        try {
            MessageBuilder<String> message = MessageBuilder.withPayload(event.payloadJson())
                    .setHeader(KafkaHeaders.TOPIC, event.topic())
                    .setHeader(KafkaHeaders.KEY, event.partitionKey());
            headers(event.headersJson()).forEach((name, value) -> message.setHeader(name, value));
            kafkaTemplate.send(message.build()).get(ACK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception exception) {
            // The relay turns this into a bounded retry. Do not include broker error text because
            // a provider/proxy can reflect sensitive request metadata.
            throw new EventBusPublishException();
        }
    }

    private Map<String, String> headers(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<Map<String, String>>() {
            });
        } catch (Exception exception) {
            throw new EventBusPublishException();
        }
    }
}
