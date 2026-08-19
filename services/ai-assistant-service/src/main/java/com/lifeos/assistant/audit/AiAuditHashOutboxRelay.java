package com.lifeos.assistant.audit;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** At-least-once bounded relay; Trust Ledger deduplicates immutable audit event IDs. */
@Component
@ConditionalOnProperty(prefix = "ai-assistant.audit-outbox", name = "relay-enabled", havingValue = "true")
public class AiAuditHashOutboxRelay {

    private final AiAuditHashOutboxTransactions transactions;
    private final AiAuditHashOutboxPublisher publisher;
    private final ExecutorService executor;

    public AiAuditHashOutboxRelay(
            AiAuditHashOutboxTransactions transactions,
            AiAuditHashOutboxPublisher publisher,
            @Qualifier("aiAuditOutboxExecutor") ExecutorService executor) {
        this.transactions = transactions;
        this.publisher = publisher;
        this.executor = executor;
    }

    @Scheduled(fixedDelayString = "${ai-assistant.audit-outbox.poll-delay:1s}")
    public void relayDueEvents() {
        List<? extends Future<?>> futures = transactions.claimBatch().stream()
                .map(claimed -> executor.submit(() -> relay(claimed)))
                .toList();
        try {
            for (Future<?> future : futures) {
                future.get();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException exception) {
            throw new IllegalStateException("AI audit outbox worker failed", exception.getCause());
        }
    }

    private void relay(ClaimedAiAuditHashOutboxEvent claimed) {
        try {
            publisher.publish(claimed);
            transactions.markPublished(claimed);
        } catch (RuntimeException exception) {
            transactions.rescheduleOrDeadLetter(claimed);
        }
    }
}
