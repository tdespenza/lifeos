package com.lifeos.calendar.outbox;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/** Bounded synchronous acknowledgement adapter over Kafka's idempotent producer configuration. */
@Component
public class KafkaCalendarEventBusPublisher implements CalendarEventBusPublisher {

    private static final Duration PUBLISH_TIMEOUT = Duration.ofSeconds(5);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public KafkaCalendarEventBusPublisher(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publish(ClaimedCalendarOutboxEvent event) {
        try {
            ProducerRecord<String, String> record = new ProducerRecord<>(event.topic(), event.partitionKey(), event.payloadJson());
            Map<String, String> headers = objectMapper.readValue(event.headersJson(), new TypeReference<>() {
            });
            headers.forEach((name, value) -> record.headers().add(name, value.getBytes(StandardCharsets.UTF_8)));
            kafkaTemplate.send(record).get(PUBLISH_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CalendarEventPublishException(exception);
        } catch (Exception exception) {
            throw new CalendarEventPublishException(exception);
        }
    }
}
