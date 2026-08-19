package com.lifeos.notification.messaging;

import com.lifeos.notification.config.NotificationProperties;
import com.lifeos.notification.delivery.FullJitterRetryPolicy;
import com.lifeos.notification.persistence.NotificationOutboxEvent;
import com.lifeos.notification.persistence.NotificationOutboxEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Short transactional claim/finalize operations around broker calls. */
@Service
public class NotificationOutboxTransactions {

    private final NotificationOutboxEventRepository repository;
    private final NotificationProperties properties;
    private final FullJitterRetryPolicy retryPolicy;
    private final Clock clock;

    public NotificationOutboxTransactions(
            NotificationOutboxEventRepository repository,
            NotificationProperties properties,
            @Qualifier("notificationOutboxRetryPolicy") FullJitterRetryPolicy retryPolicy,
            Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.retryPolicy = retryPolicy;
        this.clock = clock;
    }

    @Transactional
    public List<ClaimedOutboxEvent> claimBatch() {
        Instant now = clock.instant();
        return repository.findClaimableForUpdate(now, properties.getOutbox().getBatchSize()).stream()
                .map(event -> {
                    var leaseToken = event.claim(now, properties.getOutbox().getLeaseDuration());
                    return new ClaimedOutboxEvent(
                            event.getId(),
                            leaseToken,
                            event.getTopic(),
                            event.getPartitionKey(),
                            event.getPayloadJson(),
                            event.getHeadersJson(),
                            event.getAttemptCount());
                })
                .toList();
    }

    @Transactional
    public boolean markPublished(ClaimedOutboxEvent claimed) {
        NotificationOutboxEvent event = repository.findById(claimed.id()).orElse(null);
        if (event == null || !claimed.leaseToken().equals(event.getLeaseToken())) {
            return false;
        }
        event.markPublished(claimed.leaseToken(), clock.instant());
        return true;
    }

    @Transactional
    public boolean reschedule(ClaimedOutboxEvent claimed) {
        NotificationOutboxEvent event = repository.findById(claimed.id()).orElse(null);
        if (event == null || !claimed.leaseToken().equals(event.getLeaseToken())) {
            return false;
        }
        Instant now = clock.instant();
        event.reschedule(
                claimed.leaseToken(), "KAFKA_PUBLISH_FAILURE", now.plus(retryPolicy.nextDelay(event.getAttemptCount())));
        return true;
    }
}
