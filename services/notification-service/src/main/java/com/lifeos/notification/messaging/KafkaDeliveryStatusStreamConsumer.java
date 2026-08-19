package com.lifeos.notification.messaging;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifeos.events.v1.CloudEventV1;
import com.lifeos.events.v1.EventContract;
import com.lifeos.events.v1.NotificationChannel;
import com.lifeos.events.v1.NotificationDeliveryOutcome;
import com.lifeos.events.v1.NotificationDeliveryStatusV1;
import com.lifeos.notification.persistence.NotificationRecord;
import com.lifeos.notification.persistence.NotificationRecordRepository;
import com.lifeos.notification.read.NotificationView;
import com.lifeos.notification.stream.NotificationStreamHub;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Per-instance durable status-topic subscriber for local SSE fanout. Deployments must give each
 * notification-service replica a unique instance ID. The runtime derives an exclusive Kafka group
 * from that identity, so Kafka broadcasts a realtime delivery status to every instance and each
 * instance only fans out to its own bounded local sessions.
 */
@Component
@ConditionalOnProperty(value = "notification.kafka.realtime-consumer-enabled", havingValue = "true", matchIfMissing = true)
public class KafkaDeliveryStatusStreamConsumer {

    private final ObjectMapper objectMapper;
    private final NotificationRecordRepository recordRepository;
    private final NotificationStreamHub streamHub;

    public KafkaDeliveryStatusStreamConsumer(
            ObjectMapper objectMapper, NotificationRecordRepository recordRepository, NotificationStreamHub streamHub) {
        this.objectMapper = objectMapper;
        this.recordRepository = recordRepository;
        this.streamHub = streamHub;
    }

    @KafkaListener(
            topics = EventContract.NOTIFICATION_DELIVERY_STATUS_V1_TOPIC,
            groupId = "#{@notificationRealtimeConsumerGroup}")
    public void consume(ConsumerRecord<String, String> record) {
        if (record.value() == null || record.value().length() > 16_000) {
            throw new InvalidNotificationEventException("delivery status payload is absent or exceeds its bound");
        }
        CloudEventV1<NotificationDeliveryStatusV1> event;
        try {
            event = objectMapper.readValue(record.value(), new TypeReference<CloudEventV1<NotificationDeliveryStatusV1>>() {
            });
        } catch (Exception exception) {
            throw new InvalidNotificationEventException("delivery status is not valid contract JSON", exception);
        }
        if (!EventContract.NOTIFICATION_DELIVERY_STATUS_V1_TYPE.equals(event.type())) {
            throw new InvalidNotificationEventException("unexpected event type on delivery status topic");
        }
        NotificationDeliveryStatusV1 status = event.data();
        if (status.channel() != NotificationChannel.REALTIME || status.outcome() != NotificationDeliveryOutcome.DELIVERED) {
            return;
        }
        NotificationRecord notification = recordRepository.findById(status.notificationId()).orElse(null);
        if (notification == null || !notification.getRecipientAccountId().equals(status.recipientAccountId())) {
            throw new InvalidNotificationEventException("delivery status notification identity does not match local state");
        }
        streamHub.publish(notification.getRecipientAccountId(), NotificationView.from(notification));
    }
}
