package com.lifeos.documentvault.proof;

import com.lifeos.documentvault.config.DocumentProofOutboxProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Short claim/finalize transactions keep the database lock out of broker I/O. */
@Service
public class DocumentProofOutboxTransactions {

    public static final String TOPIC = com.lifeos.events.v1.EventContract.DOCUMENT_PROOF_REQUESTED_V1_TOPIC;
    private static final String KEY_PREFIX = "document/";
    private static final String FAILURE_CODE = "KAFKA_PUBLISH_FAILURE";

    private final DocumentProofOutboxEventRepository outboxRepository;
    private final DocumentProofOutboxDeadLetterRepository deadLetterRepository;
    private final DocumentProofRequestRepository requestRepository;
    private final DocumentProofOutboxProperties properties;
    private final DocumentProofOutboxRetryPolicy retryPolicy;
    private final Clock clock;

    public DocumentProofOutboxTransactions(
            DocumentProofOutboxEventRepository outboxRepository,
            DocumentProofOutboxDeadLetterRepository deadLetterRepository,
            DocumentProofRequestRepository requestRepository,
            DocumentProofOutboxProperties properties,
            DocumentProofOutboxRetryPolicy retryPolicy,
            Clock clock) {
        this.outboxRepository = outboxRepository;
        this.deadLetterRepository = deadLetterRepository;
        this.requestRepository = requestRepository;
        this.properties = properties;
        this.retryPolicy = retryPolicy;
        this.clock = clock;
    }

    @Transactional
    public List<ClaimedDocumentProofOutboxEvent> claimBatch() {
        Instant now = clock.instant();
        int limit = Math.min(properties.getBatchSize(), properties.getMaxConcurrentPublishes());
        return outboxRepository.findClaimableForUpdate(now, limit).stream()
                .map(event -> {
                    var lease = event.claim(now, properties.getLeaseDuration());
                    return new ClaimedDocumentProofOutboxEvent(
                            event.getId(), event.getProofRequestId(), lease, event.getEventType(), TOPIC,
                            KEY_PREFIX + event.getDocumentId(), event.getPayloadJson(), event.getAttemptCount());
                })
                .toList();
    }

    @Transactional
    public boolean markPublished(ClaimedDocumentProofOutboxEvent claimed) {
        DocumentProofOutboxEvent event = outboxRepository.findById(claimed.id()).orElse(null);
        if (event == null || !claimed.leaseToken().equals(event.getLeaseToken())) {
            return false;
        }
        event.markPublished(claimed.leaseToken(), clock.instant());
        return true;
    }

    @Transactional
    public boolean rescheduleOrDeadLetter(ClaimedDocumentProofOutboxEvent claimed) {
        DocumentProofOutboxEvent event = outboxRepository.findById(claimed.id()).orElse(null);
        if (event == null || !claimed.leaseToken().equals(event.getLeaseToken())) {
            return false;
        }
        Instant now = clock.instant();
        if (event.getAttemptCount() >= properties.getMaxAttempts()) {
            event.deadLetter(claimed.leaseToken(), FAILURE_CODE, now);
            deadLetterRepository.save(DocumentProofOutboxDeadLetter.from(event, FAILURE_CODE, now));
            requestRepository.findById(event.getProofRequestId()).ifPresent(DocumentProofRequest::markFailed);
            return false;
        }
        event.reschedule(claimed.leaseToken(), now.plus(retryPolicy.nextDelay(event.getAttemptCount())), FAILURE_CODE);
        return true;
    }
}
