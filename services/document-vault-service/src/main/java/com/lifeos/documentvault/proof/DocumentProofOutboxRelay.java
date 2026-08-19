package com.lifeos.documentvault.proof;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** At-least-once bounded relay; duplicate delivery is safe because event id is immutable. */
@Component
@ConditionalOnProperty(value = "document-vault.proof-outbox.relay-enabled", havingValue = "true")
public class DocumentProofOutboxRelay {

    private final DocumentProofOutboxTransactions transactions;
    private final DocumentProofEventPublisher publisher;
    private final ExecutorService executor;

    public DocumentProofOutboxRelay(
            DocumentProofOutboxTransactions transactions,
            DocumentProofEventPublisher publisher,
            @Qualifier("documentProofOutboxExecutor") ExecutorService executor) {
        this.transactions = transactions;
        this.publisher = publisher;
        this.executor = executor;
    }

    @Scheduled(fixedDelayString = "${document-vault.proof-outbox.poll-delay:1s}")
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
            throw new IllegalStateException("document proof outbox worker failed", exception.getCause());
        }
    }

    private void relay(ClaimedDocumentProofOutboxEvent claimed) {
        try {
            publisher.publish(claimed);
            transactions.markPublished(claimed);
        } catch (RuntimeException exception) {
            transactions.rescheduleOrDeadLetter(claimed);
        }
    }
}
