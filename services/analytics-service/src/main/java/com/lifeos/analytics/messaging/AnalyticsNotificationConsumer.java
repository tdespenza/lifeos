package com.lifeos.analytics.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifeos.analytics.projection.AnalyticsProjectionService;
import com.lifeos.analytics.observability.RequestContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/** At-least-once V2 notification projection with durable event-id dedupe. */
@Component
@ConditionalOnProperty(prefix = "analytics", name = "kafka-enabled", havingValue = "true")
public class AnalyticsNotificationConsumer {

    private static final String EVENT_TYPE = "com.lifeos.notification.requested.v2";

    private final ObjectMapper objectMapper;
    private final AnalyticsProjectionService projections;
    private final Counter processed;
    private final Counter duplicates;
    private final Counter failures;
    private final Timer processingLag;

    public AnalyticsNotificationConsumer(
            ObjectMapper objectMapper, AnalyticsProjectionService projections, MeterRegistry meterRegistry) {
        this.objectMapper = objectMapper;
        this.projections = projections;
        this.processed = meterRegistry.counter("analytics.events.processed", "event_type", EVENT_TYPE);
        this.duplicates = meterRegistry.counter("analytics.events.duplicates", "event_type", EVENT_TYPE);
        this.failures = meterRegistry.counter("analytics.events.failures", "event_type", EVENT_TYPE);
        this.processingLag = meterRegistry.timer("analytics.events.processing_lag", "event_type", EVENT_TYPE);
    }

    @KafkaListener(
            topics = "${analytics.kafka-topic}",
            groupId = "${analytics.kafka-group}",
            containerFactory = "kafkaListenerContainerFactory")
    public void consume(String payload) {
        try {
            JsonNode event = objectMapper.readTree(payload);
            UUID eventId = UUID.fromString(event.path("id").asText());
            if (!EVENT_TYPE.equals(event.path("type").asText())) {
                throw new IllegalArgumentException("unsupported analytics event type");
            }
            Instant eventTime = Instant.parse(event.path("time").asText());
            UUID correlationId = UUID.fromString(correlationText(event));
            JsonNode data = event.path("data");
            UUID recipient = UUID.fromString(data.path("recipientAccountId").asText());
            String tenant = data.path("tenantId").asText();
            try (MDC.MDCCloseable ignored = MDC.putCloseable("correlationId", correlationId.toString())) {
                ScopedValue.where(RequestContext.CORRELATION_ID, correlationId.toString()).run(() -> {
                    if (!projections.projectNotificationRequest(eventId, EVENT_TYPE, eventTime, recipient, tenant)) {
                        duplicates.increment();
                        return;
                    }
                    processed.increment();
                    processingLag.record(Math.max(0L, System.currentTimeMillis() - eventTime.toEpochMilli()),
                            java.util.concurrent.TimeUnit.MILLISECONDS);
                });
            }
        } catch (Exception exception) {
            failures.increment();
            throw new IllegalArgumentException("invalid analytics event");
        }
    }

    private static String correlationText(JsonNode event) {
        String camelCase = event.path("correlationId").asText();
        return camelCase.isBlank() ? event.path("correlationid").asText() : camelCase;
    }
}
