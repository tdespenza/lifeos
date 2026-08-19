package com.lifeos.assistant.audit;

import com.lifeos.assistant.config.AiAuditOutboxProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Short claim/finalize transactions keep database locks out of broker I/O. */
@Service
public class AiAuditHashOutboxTransactions {

    public static final String TOPIC = com.lifeos.events.v1.EventContract.AI_AUDIT_HASH_REQUESTED_V1_TOPIC;
    private static final String FAILURE_CODE = "KAFKA_PUBLISH_FAILURE";

    private final AiAuditHashOutboxEventRepository outboxRepository;
    private final AiAuditHashOutboxDeadLetterRepository deadLetterRepository;
    private final AiAuditOutboxProperties properties;
    private final Clock clock;

    public AiAuditHashOutboxTransactions(
            AiAuditHashOutboxEventRepository outboxRepository,
            AiAuditHashOutboxDeadLetterRepository deadLetterRepository,
            AiAuditOutboxProperties properties,
            Clock clock) {
        this.outboxRepository = outboxRepository;
        this.deadLetterRepository = deadLetterRepository;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public List<ClaimedAiAuditHashOutboxEvent> claimBatch() {
        Instant now = clock.instant();
        int limit = Math.min(properties.getBatchSize(), properties.getMaxConcurrentPublishes());
        return outboxRepository.findClaimableForUpdate(now, PageRequest.of(0, limit)).stream()
                .map(event -> new ClaimedAiAuditHashOutboxEvent(
                        event.getId(), event.getAuditEventId(), event.claim(now, properties.getLeaseDuration()),
                        event.getEventType(), event.getTopic(), event.getAuditEventId().toString(),
                        event.getPayloadJson(), event.getAttemptCount()))
                .toList();
    }

    @Transactional
    public boolean markPublished(ClaimedAiAuditHashOutboxEvent claimed) {
        AiAuditHashOutboxEvent event = outboxRepository.findById(claimed.id()).orElse(null);
        if (event == null || !claimed.leaseToken().equals(event.getLeaseToken())) {
            return false;
        }
        event.markPublished(claimed.leaseToken(), clock.instant());
        return true;
    }

    @Transactional
    public boolean rescheduleOrDeadLetter(ClaimedAiAuditHashOutboxEvent claimed) {
        AiAuditHashOutboxEvent event = outboxRepository.findById(claimed.id()).orElse(null);
        if (event == null || !claimed.leaseToken().equals(event.getLeaseToken())) {
            return false;
        }
        Instant now = clock.instant();
        if (event.getAttemptCount() >= properties.getMaxAttempts()) {
            event.deadLetter(claimed.leaseToken(), FAILURE_CODE, now);
            deadLetterRepository.save(AiAuditHashOutboxDeadLetter.from(event, FAILURE_CODE, now));
            return false;
        }
        event.reschedule(claimed.leaseToken(), now.plus(backoff(event.getAttemptCount())), FAILURE_CODE);
        return true;
    }

    private Duration backoff(int attempt) {
        long multiplier = 1L << Math.min(Math.max(attempt - 1, 0), 20);
        Duration candidate;
        try {
            candidate = properties.getInitialBackoff().multipliedBy(multiplier);
        } catch (ArithmeticException overflow) {
            candidate = properties.getMaxBackoff();
        }
        return candidate.compareTo(properties.getMaxBackoff()) > 0 ? properties.getMaxBackoff() : candidate;
    }
}
