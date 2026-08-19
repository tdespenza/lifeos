package com.lifeos.notification.messaging;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifeos.events.v1.CloudEventV1;
import com.lifeos.events.v1.EventContract;
import com.lifeos.events.v1.NotificationRequestedV1;
import com.lifeos.events.v1.NotificationRequestedV2;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Durable consumer for versioned notification commands. Offsets commit only after the inbox
 * transaction returns, and duplicates acknowledge safely without creating another delivery set.
 */
@Component
@ConditionalOnProperty(value = "notification.kafka.consumer-enabled", havingValue = "true", matchIfMissing = true)
public class KafkaNotificationRequestedConsumer {

    private final ObjectMapper objectMapper;
    private final NotificationIngressService ingressService;

    public KafkaNotificationRequestedConsumer(ObjectMapper objectMapper, NotificationIngressService ingressService) {
        this.objectMapper = objectMapper;
        this.ingressService = ingressService;
    }

    @KafkaListener(
            topics = EventContract.NOTIFICATION_REQUESTED_V1_TOPIC,
            groupId = "${notification.kafka.consumer-group:notification-service-v1}")
    public void consume(ConsumerRecord<String, String> record) {
        CloudEventV1<NotificationRequestedV1> event = parse(record, new TypeReference<>() {
        });
        if (!EventContract.NOTIFICATION_REQUESTED_V1_TYPE.equals(event.type())) {
            throw new InvalidNotificationEventException("unexpected event type on notification requested topic");
        }
        verifyRecipientKey(record, event.data().recipientAccountId().toString());
        ingressService.accept(event, record.value());
    }

    /** Consumes the additive time-zone-aware contract on its distinct versioned topic. */
    @KafkaListener(
            topics = EventContract.NOTIFICATION_REQUESTED_V2_TOPIC,
            groupId = "${notification.kafka.consumer-group:notification-service-v1}")
    public void consumeV2(ConsumerRecord<String, String> record) {
        CloudEventV1<NotificationRequestedV2> event = parse(record, new TypeReference<>() {
        });
        if (!EventContract.NOTIFICATION_REQUESTED_V2_TYPE.equals(event.type())) {
            throw new InvalidNotificationEventException("unexpected event type on notification requested topic");
        }
        verifyRecipientKey(record, event.data().recipientAccountId().toString());
        ingressService.acceptV2(event, record.value());
    }

    private <T> CloudEventV1<T> parse(ConsumerRecord<String, String> record, TypeReference<CloudEventV1<T>> type) {
        if (record.value() == null || record.value().length() > 32_000) {
            throw new InvalidNotificationEventException("notification event payload is absent or exceeds its bound");
        }
        try {
            return objectMapper.readValue(record.value(), type);
        } catch (Exception exception) {
            throw new InvalidNotificationEventException("notification event is not valid contract JSON", exception);
        }
    }

    private static void verifyRecipientKey(ConsumerRecord<String, String> record, String recipientAccountId) {
        if (record.key() == null || !record.key().equals(recipientAccountId)) {
            throw new InvalidNotificationEventException(
                    "notification requested Kafka key must match the recipient account for ordered tenant-safe processing");
        }
    }
}
