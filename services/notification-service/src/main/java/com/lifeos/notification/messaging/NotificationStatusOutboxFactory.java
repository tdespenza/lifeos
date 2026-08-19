package com.lifeos.notification.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifeos.events.v1.CloudEventV1;
import com.lifeos.events.v1.EventContract;
import com.lifeos.events.v1.NotificationDeliveryOutcome;
import com.lifeos.events.v1.NotificationDeliveryStatusV1;
import com.lifeos.notification.persistence.NotificationDelivery;
import com.lifeos.notification.persistence.NotificationOutboxEvent;
import com.lifeos.notification.persistence.NotificationRecord;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Serializes delivery outcomes into immutable CloudEvents written through the local outbox. */
@Component
public class NotificationStatusOutboxFactory {

    private static final URI SOURCE = URI.create("urn:lifeos:notification-service");

    private final ObjectMapper objectMapper;

    public NotificationStatusOutboxFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public NotificationOutboxEvent create(
            NotificationDelivery delivery,
            NotificationRecord notification,
            NotificationDeliveryOutcome outcome,
            String reasonCode,
            Instant now) {
        UUID id = UUID.randomUUID();
        NotificationDeliveryStatusV1 data = new NotificationDeliveryStatusV1(
                delivery.getNotificationId(),
                delivery.getSourceEventId(),
                delivery.getRecipientAccountId(),
                delivery.getChannel(),
                outcome,
                delivery.getAttemptCount(),
                reasonCode,
                now);
        CloudEventV1<NotificationDeliveryStatusV1> event = new CloudEventV1<>(
                id,
                EventContract.CLOUD_EVENTS_SPEC_VERSION,
                SOURCE,
                EventContract.NOTIFICATION_DELIVERY_STATUS_V1_TYPE,
                "delivery/" + delivery.getId() + "/" + delivery.getAttemptCount(),
                now,
                "application/json",
                notification.getCorrelationId(),
                data);
        return NotificationOutboxEvent.pending(
                id,
                delivery.getId(),
                delivery.getAttemptCount(),
                event.type(),
                EventContract.NOTIFICATION_DELIVERY_STATUS_V1_TOPIC,
                delivery.getRecipientAccountId().toString(),
                json(event),
                json(Map.of(
                        "ce_id", id.toString(),
                        "ce_type", event.type(),
                        "ce_source", event.source().toString(),
                        "correlation_id", notification.getCorrelationId().toString())),
                now);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("notification event serialization failed", exception);
        }
    }
}
