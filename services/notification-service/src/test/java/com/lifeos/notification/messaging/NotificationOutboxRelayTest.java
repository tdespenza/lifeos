package com.lifeos.notification.messaging;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lifeos.notification.delivery.NotificationMetrics;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NotificationOutboxRelayTest {

    @Test
    void retainsAndReschedulesAnEventWhenKafkaDoesNotAcknowledge() {
        NotificationOutboxTransactions transactions = org.mockito.Mockito.mock(NotificationOutboxTransactions.class);
        EventBusPublisher publisher = org.mockito.Mockito.mock(EventBusPublisher.class);
        NotificationMetrics metrics = org.mockito.Mockito.mock(NotificationMetrics.class);
        ClaimedOutboxEvent event = new ClaimedOutboxEvent(
                UUID.randomUUID(), UUID.randomUUID(), "topic", "account", "{}", "{}", 1);
        when(transactions.claimBatch()).thenReturn(List.of(event));
        doThrow(new EventBusPublishException()).when(publisher).publish(event);
        when(transactions.reschedule(event)).thenReturn(true);

        new NotificationOutboxRelay(transactions, publisher, metrics).relayDueEvents();

        verify(transactions).reschedule(event);
        verify(metrics).recordOutbox(false);
    }

    @Test
    void marksAnAcknowledgedEventPublished() {
        NotificationOutboxTransactions transactions = org.mockito.Mockito.mock(NotificationOutboxTransactions.class);
        EventBusPublisher publisher = org.mockito.Mockito.mock(EventBusPublisher.class);
        NotificationMetrics metrics = org.mockito.Mockito.mock(NotificationMetrics.class);
        ClaimedOutboxEvent event = new ClaimedOutboxEvent(
                UUID.randomUUID(), UUID.randomUUID(), "topic", "account", "{}", "{}", 1);
        when(transactions.claimBatch()).thenReturn(List.of(event));
        when(transactions.markPublished(event)).thenReturn(true);

        new NotificationOutboxRelay(transactions, publisher, metrics).relayDueEvents();

        verify(publisher).publish(event);
        verify(transactions).markPublished(event);
        verify(metrics).recordOutbox(true);
    }
}
