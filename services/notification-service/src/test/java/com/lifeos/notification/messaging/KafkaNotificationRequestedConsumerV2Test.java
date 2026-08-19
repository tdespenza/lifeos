package com.lifeos.notification.messaging;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifeos.events.v1.CloudEventV1;
import com.lifeos.events.v1.EventContract;
import com.lifeos.events.v1.NotificationChannel;
import com.lifeos.events.v1.NotificationPriority;
import com.lifeos.events.v1.NotificationRequestedV2;
import java.net.URI;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

/** Contract boundary proving the dedicated V2 topic reaches the additive time-zone consumer path. */
class KafkaNotificationRequestedConsumerV2Test {

    @Test
    void acceptsTheVersionedTimezoneAwareNotificationContract() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        NotificationIngressService ingressService = mock(NotificationIngressService.class);
        KafkaNotificationRequestedConsumer consumer = new KafkaNotificationRequestedConsumer(objectMapper, ingressService);
        CloudEventV1<NotificationRequestedV2> event = event();
        String payload = objectMapper.writeValueAsString(event);

        consumer.consumeV2(new ConsumerRecord<>(
                EventContract.NOTIFICATION_REQUESTED_V2_TOPIC,
                0,
                1L,
                event.data().recipientAccountId().toString(),
                payload));

        verify(ingressService).acceptV2(
                argThat(value -> value.type().equals(EventContract.NOTIFICATION_REQUESTED_V2_TYPE)
                        && value.data().eventTimeZone().equals("America/Chicago")),
                eq(payload));
    }

    private static CloudEventV1<NotificationRequestedV2> event() {
        UUID notificationId = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        return new CloudEventV1<>(
                UUID.randomUUID(),
                EventContract.CLOUD_EVENTS_SPEC_VERSION,
                URI.create("urn:lifeos:calendar-service"),
                EventContract.NOTIFICATION_REQUESTED_V2_TYPE,
                "notification/" + notificationId,
                Instant.parse("2026-08-18T12:00:00Z"),
                "application/json",
                UUID.randomUUID(),
                new NotificationRequestedV2(
                        notificationId,
                        recipient,
                        recipient.toString(),
                        "calendar.reminder",
                        NotificationPriority.NORMAL,
                        "Calendar reminder",
                        "An upcoming calendar event is starting soon.",
                        URI.create("lifeos://calendar/events/" + UUID.randomUUID()),
                        Set.of(NotificationChannel.REALTIME),
                        Instant.parse("2026-08-18T13:00:00Z"),
                        "America/Chicago"));
    }
}
