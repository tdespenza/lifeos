package com.lifeos.notification.messaging;

import com.lifeos.notification.delivery.NotificationMetrics;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Kafka-compatible application outbox relay. It is at-least-once by design: a crash after broker
 * acknowledgement but before {@code PUBLISHED} commit can republish, which consumers must dedupe
 * using the immutable CloudEvents ID.
 */
@Component
@ConditionalOnProperty(value = "notification.outbox.relay-enabled", havingValue = "true", matchIfMissing = true)
public class NotificationOutboxRelay {

    private final NotificationOutboxTransactions transactions;
    private final EventBusPublisher publisher;
    private final NotificationMetrics metrics;

    public NotificationOutboxRelay(
            NotificationOutboxTransactions transactions, EventBusPublisher publisher, NotificationMetrics metrics) {
        this.transactions = transactions;
        this.publisher = publisher;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${notification.outbox.poll-delay:1s}")
    public void relayDueEvents() {
        transactions.claimBatch().forEach(this::relay);
    }

    private void relay(ClaimedOutboxEvent event) {
        try {
            publisher.publish(event);
            if (transactions.markPublished(event)) {
                metrics.recordOutbox(true);
            }
        } catch (RuntimeException exception) {
            if (transactions.reschedule(event)) {
                metrics.recordOutbox(false);
            }
        }
    }
}
