package com.lifeos.calendar.outbox;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** At-least-once Kafka relay; consumers use immutable CloudEvent IDs for durable deduplication. */
@Component
@ConditionalOnProperty(value = "calendar.outbox.relay-enabled", havingValue = "true", matchIfMissing = true)
public class CalendarOutboxRelay {

    private final CalendarOutboxTransactions transactions;
    private final CalendarEventBusPublisher publisher;
    private final CalendarMessagingMetrics metrics;
    private final ExecutorService executor;

    public CalendarOutboxRelay(
            CalendarOutboxTransactions transactions,
            CalendarEventBusPublisher publisher,
            CalendarMessagingMetrics metrics,
            @Qualifier("calendarOutboxExecutor") ExecutorService executor) {
        this.transactions = transactions;
        this.publisher = publisher;
        this.metrics = metrics;
        this.executor = executor;
    }

    @Scheduled(fixedDelayString = "${calendar.outbox.poll-delay:1s}")
    public void relayDueEvents() {
        List<? extends Future<?>> futures = transactions.claimBatch().stream()
                .map(claimed -> executor.submit(() -> relay(claimed)))
                .toList();
        awaitAll(futures);
    }

    private void relay(ClaimedCalendarOutboxEvent claimed) {
        try {
            publisher.publish(claimed);
            if (transactions.markPublished(claimed)) {
                metrics.recordOutbox(true);
            }
        } catch (RuntimeException exception) {
            if (transactions.rescheduleOrDeadLetter(claimed)) {
                metrics.recordOutbox(false);
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
            throw new IllegalStateException("calendar outbox worker unexpectedly failed", exception.getCause());
        }
    }
}
