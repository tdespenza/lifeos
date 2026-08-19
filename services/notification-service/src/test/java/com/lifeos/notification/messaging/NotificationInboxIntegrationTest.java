package com.lifeos.notification.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifeos.events.v1.CloudEventV1;
import com.lifeos.events.v1.EventContract;
import com.lifeos.events.v1.NotificationChannel;
import com.lifeos.events.v1.NotificationPriority;
import com.lifeos.events.v1.NotificationRequestedV1;
import com.lifeos.events.v1.NotificationRequestedV2;
import com.lifeos.notification.persistence.NotificationDeadLetterRepository;
import com.lifeos.notification.persistence.NotificationDeliveryRepository;
import com.lifeos.notification.persistence.NotificationEndpointRegistrationIdempotencyRepository;
import com.lifeos.notification.persistence.NotificationEndpointRepository;
import com.lifeos.notification.persistence.NotificationInboxEventRepository;
import com.lifeos.notification.persistence.NotificationOutboxEventRepository;
import com.lifeos.notification.persistence.NotificationRecordRepository;
import com.lifeos.notification.persistence.NotificationSubjectSequenceRepository;
import java.net.URI;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class NotificationInboxIntegrationTest {

    @Autowired
    private NotificationIngressService ingressService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private NotificationDeliveryRepository deliveryRepository;

    @Autowired
    private NotificationRecordRepository recordRepository;

    @Autowired
    private NotificationInboxEventRepository inboxRepository;

    @Autowired
    private NotificationOutboxEventRepository outboxRepository;

    @Autowired
    private NotificationDeadLetterRepository deadLetterRepository;

    @Autowired
    private NotificationEndpointRegistrationIdempotencyRepository endpointIdempotencyRepository;

    @Autowired
    private NotificationEndpointRepository endpointRepository;

    @Autowired
    private NotificationSubjectSequenceRepository sequenceRepository;

    @BeforeEach
    void cleanDatabase() {
        deadLetterRepository.deleteAll();
        outboxRepository.deleteAll();
        deliveryRepository.deleteAll();
        endpointIdempotencyRepository.deleteAll();
        endpointRepository.deleteAll();
        recordRepository.deleteAll();
        inboxRepository.deleteAll();
        sequenceRepository.deleteAll();
    }

    @Test
    void persistsOneInboxNotificationAndDeliveryAcrossKafkaRedelivery() throws Exception {
        CloudEventV1<NotificationRequestedV1> event = event("Event starts soon");
        String payload = objectMapper.writeValueAsString(event);

        NotificationIngressResult first = ingressService.accept(event, payload);
        NotificationIngressResult duplicate = ingressService.accept(event, payload);

        assertEquals(false, first.duplicate());
        assertEquals(true, duplicate.duplicate());
        assertEquals(first.notificationId(), duplicate.notificationId());
        assertEquals(1, inboxRepository.count());
        assertEquals(1, recordRepository.count());
        assertEquals(1, deliveryRepository.count());
        assertEquals(1, recordRepository.findAll().getFirst().getSequenceNumber());
    }

    @Test
    void rejectsAChangedPayloadReusingTheSameCloudEventsId() throws Exception {
        CloudEventV1<NotificationRequestedV1> original = event("Original body");
        ingressService.accept(original, objectMapper.writeValueAsString(original));
        CloudEventV1<NotificationRequestedV1> altered = new CloudEventV1<>(
                original.id(),
                original.specversion(),
                original.source(),
                original.type(),
                original.subject(),
                original.time(),
                original.datacontenttype(),
                original.correlationId(),
                new NotificationRequestedV1(
                        original.data().notificationId(),
                        original.data().recipientAccountId(),
                        original.data().tenantId(),
                        original.data().category(),
                        original.data().priority(),
                        original.data().title(),
                        "Altered body",
                        original.data().actionUri(),
                        original.data().requestedChannels(),
                        original.data().expiresAt()));

        assertThrows(
                NotificationEventIdConflictException.class,
                () -> ingressService.accept(altered, objectMapper.writeValueAsString(altered)));
        assertEquals(1, recordRepository.count());
    }

    @Test
    void rejectsCrossTenantRecipientClaimsBeforeAnyInboxOrDeliveryStateIsCommitted() throws Exception {
        CloudEventV1<NotificationRequestedV1> valid = event("Private reminder");
        NotificationRequestedV1 request = valid.data();
        CloudEventV1<NotificationRequestedV1> crossTenant = new CloudEventV1<>(
                valid.id(),
                valid.specversion(),
                valid.source(),
                valid.type(),
                valid.subject(),
                valid.time(),
                valid.datacontenttype(),
                valid.correlationId(),
                new NotificationRequestedV1(
                        request.notificationId(),
                        request.recipientAccountId(),
                        UUID.randomUUID().toString(),
                        request.category(),
                        request.priority(),
                        request.title(),
                        request.body(),
                        request.actionUri(),
                        request.requestedChannels(),
                        request.expiresAt()));

        assertThrows(
                InvalidNotificationEventException.class,
                () -> ingressService.accept(crossTenant, objectMapper.writeValueAsString(crossTenant)));
        assertEquals(0, inboxRepository.count());
        assertEquals(0, recordRepository.count());
        assertEquals(0, deliveryRepository.count());
    }

    @Test
    void persistsTheV2TimezoneAwareCalendarCommandWithoutChangingTheV1DeliveryModel() throws Exception {
        UUID notificationId = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        CloudEventV1<NotificationRequestedV2> event = new CloudEventV1<>(
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

        NotificationIngressResult result = ingressService.acceptV2(event, objectMapper.writeValueAsString(event));

        assertEquals(false, result.duplicate());
        assertEquals(1, inboxRepository.count());
        assertEquals(1, recordRepository.count());
        assertEquals(1, deliveryRepository.count());
        assertEquals("America/Chicago", recordRepository.findAll().getFirst().getEventTimeZone());
    }

    private static CloudEventV1<NotificationRequestedV1> event(String body) {
        UUID notificationId = UUID.randomUUID();
        UUID recipientAccountId = UUID.randomUUID();
        return new CloudEventV1<>(
                UUID.randomUUID(),
                EventContract.CLOUD_EVENTS_SPEC_VERSION,
                URI.create("urn:lifeos:calendar-service"),
                EventContract.NOTIFICATION_REQUESTED_V1_TYPE,
                "notification/" + notificationId,
                Instant.parse("2026-08-17T12:00:00Z"),
                "application/json",
                UUID.randomUUID(),
                new NotificationRequestedV1(
                        notificationId,
                        recipientAccountId,
                        recipientAccountId.toString(),
                        "calendar.reminder",
                        NotificationPriority.NORMAL,
                        "Reminder",
                        body,
                        null,
                        Set.of(NotificationChannel.REALTIME),
                        null));
    }
}
