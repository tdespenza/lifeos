package com.lifeos.notification.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.lifeos.events.v1.CloudEventV1;
import com.lifeos.events.v1.EventContract;
import com.lifeos.events.v1.NotificationChannel;
import com.lifeos.events.v1.NotificationPriority;
import com.lifeos.events.v1.NotificationRequestedV1;
import com.lifeos.notification.persistence.NotificationInboxEvent;
import com.lifeos.notification.persistence.NotificationInboxEventRepository;
import com.lifeos.notification.security.SensitiveValueDigest;
import java.net.URI;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class NotificationIngressServiceTest {

    @Test
    void acknowledgesSamePayloadDuplicateButRejectsConflictingEventId() {
        NotificationIngressTransactions transactions = org.mockito.Mockito.mock(NotificationIngressTransactions.class);
        NotificationInboxEventRepository inbox = org.mockito.Mockito.mock(NotificationInboxEventRepository.class);
        NotificationIngressService service = new NotificationIngressService(transactions, inbox);
        CloudEventV1<NotificationRequestedV1> event = event();
        String payload = "canonical-payload";
        NotificationInboxEvent existing = NotificationInboxEvent.received(
                event.id(), event.source().toString(), event.type(), event.correlationId(), SensitiveValueDigest.sha256(payload), Instant.now());
        UUID notificationId = event.data().notificationId();
        existing.markProcessed(notificationId, Instant.now());
        when(transactions.acceptOnce(any(), anyString())).thenThrow(new DataIntegrityViolationException("unique"));
        when(inbox.findById(event.id())).thenReturn(Optional.of(existing));

        NotificationIngressResult replay = service.accept(event, payload);

        assertEquals(notificationId, replay.notificationId());
        assertEquals(true, replay.duplicate());
        assertThrows(NotificationEventIdConflictException.class, () -> service.accept(event, "altered-payload"));
    }

    private static CloudEventV1<NotificationRequestedV1> event() {
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
                        "Event starts soon",
                        null,
                        Set.of(NotificationChannel.REALTIME),
                        null));
    }
}
