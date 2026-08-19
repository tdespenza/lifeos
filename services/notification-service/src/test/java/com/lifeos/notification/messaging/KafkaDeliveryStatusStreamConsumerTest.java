package com.lifeos.notification.messaging;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifeos.events.v1.CloudEventV1;
import com.lifeos.events.v1.EventContract;
import com.lifeos.events.v1.NotificationChannel;
import com.lifeos.events.v1.NotificationDeliveryOutcome;
import com.lifeos.events.v1.NotificationDeliveryStatusV1;
import com.lifeos.events.v1.NotificationPriority;
import com.lifeos.events.v1.NotificationRequestedV1;
import com.lifeos.notification.persistence.NotificationRecord;
import com.lifeos.notification.persistence.NotificationRecordRepository;
import com.lifeos.notification.stream.NotificationStreamHub;
import java.net.URI;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

class KafkaDeliveryStatusStreamConsumerTest {

    @Test
    void fansOutDurableRealtimeStatusToThisInstancesLocalHub() throws Exception {
        NotificationRecordRepository records = org.mockito.Mockito.mock(NotificationRecordRepository.class);
        NotificationStreamHub streamHub = org.mockito.Mockito.mock(NotificationStreamHub.class);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        KafkaDeliveryStatusStreamConsumer consumer =
                new KafkaDeliveryStatusStreamConsumer(objectMapper, records, streamHub);
        UUID accountId = UUID.randomUUID();
        UUID notificationId = UUID.randomUUID();
        UUID sourceEventId = UUID.randomUUID();
        NotificationRecord notification = NotificationRecord.from(
                sourceEventId,
                UUID.randomUUID(),
                new NotificationRequestedV1(
                        notificationId,
                        accountId,
                        accountId.toString(),
                        "calendar.reminder",
                        NotificationPriority.NORMAL,
                        "Reminder",
                        "Private calendar detail",
                        null,
                        Set.of(NotificationChannel.REALTIME),
                        null),
                4,
                Instant.parse("2026-08-17T12:00:00Z"));
        when(records.findById(notificationId)).thenReturn(Optional.of(notification));
        CloudEventV1<NotificationDeliveryStatusV1> status = new CloudEventV1<>(
                UUID.randomUUID(),
                EventContract.CLOUD_EVENTS_SPEC_VERSION,
                URI.create("urn:lifeos:notification-service"),
                EventContract.NOTIFICATION_DELIVERY_STATUS_V1_TYPE,
                "notification/" + notificationId + "/realtime",
                Instant.parse("2026-08-17T12:00:01Z"),
                "application/json",
                UUID.randomUUID(),
                new NotificationDeliveryStatusV1(
                        notificationId,
                        sourceEventId,
                        accountId,
                        NotificationChannel.REALTIME,
                        NotificationDeliveryOutcome.DELIVERED,
                        1,
                        "DELIVERED",
                        Instant.parse("2026-08-17T12:00:01Z")));

        consumer.consume(new ConsumerRecord<>(
                EventContract.NOTIFICATION_DELIVERY_STATUS_V1_TOPIC,
                0,
                0L,
                accountId.toString(),
                objectMapper.writeValueAsString(status)));

        verify(streamHub).publish(eq(accountId), argThat(view -> view.id().equals(notificationId) && view.sequence() == 4));
    }
}
