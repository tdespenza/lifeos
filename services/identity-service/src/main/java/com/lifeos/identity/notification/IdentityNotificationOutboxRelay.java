package com.lifeos.identity.notification;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** At-least-once recovery notification relay with bounded concurrency and a durable DLQ. */
@Component
@ConditionalOnProperty(
        value = "identity.recovery-notification.relay-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class IdentityNotificationOutboxRelay {

    private final IdentityNotificationOutboxTransactions transactions;
    private final IdentityNotificationEventPublisher publisher;
    private final IdentityNotificationOutboxMetrics metrics;
    private final ExecutorService executor;

    public IdentityNotificationOutboxRelay(
            IdentityNotificationOutboxTransactions transactions,
            IdentityNotificationEventPublisher publisher,
            IdentityNotificationOutboxMetrics metrics,
            @Qualifier("identityNotificationOutboxExecutor") ExecutorService executor) {
        this.transactions = transactions;
        this.publisher = publisher;
        this.metrics = metrics;
        this.executor = executor;
    }

    @Scheduled(fixedDelayString = "${identity.recovery-notification.poll-delay:1s}")
    public void relayDueEvents() {
        List<? extends Future<?>> futures = transactions.claimBatch().stream()
                .map(claimed -> executor.submit(() -> relay(claimed)))
                .toList();
        awaitAll(futures);
    }

    private void relay(ClaimedIdentityNotificationOutboxEvent claimed) {
        try {
            publisher.publish(claimed);
            if (transactions.markPublished(claimed)) {
                metrics.recordPublished();
            }
        } catch (RuntimeException exception) {
            if (transactions.rescheduleOrDeadLetter(claimed)) {
                metrics.recordRetry();
            } else {
                metrics.recordDeadLetter();
            }
        }
    }

    private static void awaitAll(List<? extends Future<?>> futures) {
        try {
            for (Future<?> future : futures) {
                future.get();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException exception) {
            throw new IllegalStateException(
                    "identity notification outbox worker unexpectedly failed", exception.getCause());
        }
    }
}
