package com.lifeos.identity.notification;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Short transactional claim/finalize operations for Identity's recovery notification outbox. */
@Service
public class IdentityNotificationOutboxTransactions {

    private static final String PUBLISH_FAILURE = "KAFKA_PUBLISH_FAILURE";

    private final IdentityNotificationOutboxEventRepository outboxRepository;
    private final IdentityNotificationOutboxDeadLetterRepository deadLetterRepository;
    private final IdentityRecoveryNotificationProperties properties;
    private final IdentityNotificationRetryPolicy retryPolicy;
    private final Clock clock;

    @Autowired
    public IdentityNotificationOutboxTransactions(
            IdentityNotificationOutboxEventRepository outboxRepository,
            IdentityNotificationOutboxDeadLetterRepository deadLetterRepository,
            IdentityRecoveryNotificationProperties properties,
            IdentityNotificationRetryPolicy retryPolicy) {
        this(outboxRepository, deadLetterRepository, properties, retryPolicy, Clock.systemUTC());
    }

    IdentityNotificationOutboxTransactions(
            IdentityNotificationOutboxEventRepository outboxRepository,
            IdentityNotificationOutboxDeadLetterRepository deadLetterRepository,
            IdentityRecoveryNotificationProperties properties,
            IdentityNotificationRetryPolicy retryPolicy,
            Clock clock) {
        this.outboxRepository = outboxRepository;
        this.deadLetterRepository = deadLetterRepository;
        this.properties = properties;
        this.retryPolicy = retryPolicy;
        this.clock = clock;
    }

    @Transactional
    public List<ClaimedIdentityNotificationOutboxEvent> claimBatch() {
        Instant now = clock.instant();
        int limit = Math.min(properties.getBatchSize(), properties.getMaxConcurrentPublishes());
        return outboxRepository.findClaimableForUpdate(now, limit).stream()
                .map(event -> {
                    var lease = event.claim(now, properties.getLeaseDuration());
                    return new ClaimedIdentityNotificationOutboxEvent(
                            event.getId(),
                            lease,
                            event.getTopic(),
                            event.getPartitionKey(),
                            event.getPayloadJson(),
                            event.getHeadersJson(),
                            event.getAttemptCount());
                })
                .toList();
    }

    @Transactional
    public boolean markPublished(ClaimedIdentityNotificationOutboxEvent claimed) {
        IdentityNotificationOutboxEvent event = outboxRepository.findById(claimed.id()).orElse(null);
        if (event == null || !claimed.leaseToken().equals(event.getLeaseToken())) {
            return false;
        }
        event.markPublished(claimed.leaseToken(), clock.instant());
        return true;
    }

    @Transactional
    public boolean rescheduleOrDeadLetter(ClaimedIdentityNotificationOutboxEvent claimed) {
        IdentityNotificationOutboxEvent event = outboxRepository.findById(claimed.id()).orElse(null);
        if (event == null || !claimed.leaseToken().equals(event.getLeaseToken())) {
            return false;
        }
        if (event.getAttemptCount() >= properties.getMaxAttempts()) {
            event.deadLetter(claimed.leaseToken(), PUBLISH_FAILURE);
            deadLetterRepository.save(IdentityNotificationOutboxDeadLetter.from(
                    event, PUBLISH_FAILURE, clock.instant()));
            return false;
        }
        event.reschedule(
                claimed.leaseToken(),
                clock.instant().plus(retryPolicy.nextDelay(event.getAttemptCount())),
                PUBLISH_FAILURE);
        return true;
    }
}
