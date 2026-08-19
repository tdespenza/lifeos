package com.lifeos.notification.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lifeos.events.v1.NotificationChannel;
import com.lifeos.events.v1.NotificationPriority;
import com.lifeos.events.v1.NotificationRequestedV1;
import com.lifeos.notification.config.NotificationProperties;
import com.lifeos.notification.messaging.NotificationStatusOutboxFactory;
import com.lifeos.notification.persistence.DeliveryState;
import com.lifeos.notification.persistence.NotificationDeadLetterRepository;
import com.lifeos.notification.persistence.NotificationDelivery;
import com.lifeos.notification.persistence.NotificationDeliveryRepository;
import com.lifeos.notification.persistence.NotificationEndpointRepository;
import com.lifeos.notification.persistence.NotificationOutboxEvent;
import com.lifeos.notification.persistence.NotificationOutboxEventRepository;
import com.lifeos.notification.persistence.NotificationRecord;
import com.lifeos.notification.persistence.NotificationRecordRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NotificationDeliveryTransactionsTest {

    @Test
    void persistsADeadLetterAndStatusOutboxForPermanentProviderFailure() {
        NotificationDeliveryRepository deliveries = org.mockito.Mockito.mock(NotificationDeliveryRepository.class);
        NotificationRecordRepository records = org.mockito.Mockito.mock(NotificationRecordRepository.class);
        NotificationEndpointRepository endpoints = org.mockito.Mockito.mock(NotificationEndpointRepository.class);
        NotificationDeadLetterRepository deadLetters = org.mockito.Mockito.mock(NotificationDeadLetterRepository.class);
        NotificationOutboxEventRepository outbox = org.mockito.Mockito.mock(NotificationOutboxEventRepository.class);
        NotificationStatusOutboxFactory outboxFactory = org.mockito.Mockito.mock(NotificationStatusOutboxFactory.class);
        Instant now = Instant.parse("2026-08-17T12:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        NotificationProperties properties = properties();
        NotificationDeliveryTransactions transactions = new NotificationDeliveryTransactions(
                deliveries,
                records,
                endpoints,
                deadLetters,
                outbox,
                outboxFactory,
                new FullJitterRetryPolicy(Duration.ofSeconds(1), Duration.ofMinutes(1), new Random(3)),
                properties,
                clock);
        UUID notificationId = UUID.randomUUID();
        UUID sourceEventId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        NotificationDelivery delivery = NotificationDelivery.pending(
                notificationId, sourceEventId, accountId, NotificationChannel.EMAIL, null, now);
        ClaimedDelivery claim = new ClaimedDelivery(delivery.getId(), delivery.claim(now, Duration.ofSeconds(30)));
        NotificationRecord record = NotificationRecord.from(
                sourceEventId,
                UUID.randomUUID(),
                new NotificationRequestedV1(
                        notificationId,
                        accountId,
                        "tenant-a",
                        "calendar.reminder",
                        NotificationPriority.NORMAL,
                        "Reminder",
                        "Body",
                        null,
                        Set.of(NotificationChannel.EMAIL),
                        null),
                1,
                now);
        NotificationOutboxEvent statusEvent = NotificationOutboxEvent.pending(
                UUID.randomUUID(),
                delivery.getId(),
                delivery.getAttemptCount(),
                "com.lifeos.notification.delivery-status.v1",
                "lifeos.notification.delivery-status.v1",
                accountId.toString(),
                "{}",
                "{}",
                now);
        when(deliveries.findByIdForUpdate(delivery.getId())).thenReturn(Optional.of(delivery));
        when(records.findById(notificationId)).thenReturn(Optional.of(record));
        when(deadLetters.existsByDeliveryId(delivery.getId())).thenReturn(false);
        when(outboxFactory.create(any(), any(), any(), any(), any())).thenReturn(statusEvent);

        DeliveryCompletion completion = transactions.complete(
                claim, ProviderDeliveryResult.permanentFailure("PROVIDER_REJECTED", false));

        assertEquals(DeliveryState.DEAD_LETTERED, delivery.getState());
        assertEquals("PROVIDER_REJECTED", completion.reasonCode());
        verify(deadLetters).save(any());
        verify(outbox).save(statusEvent);
    }

    private static NotificationProperties properties() {
        NotificationProperties properties = new NotificationProperties();
        properties.setEndpointEncryptionKey("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        properties.setIdempotencySecret("notification-test-idempotency-secret-with-32-bytes");
        return properties;
    }
}
