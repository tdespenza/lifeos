package com.lifeos.notification.delivery;

import com.lifeos.events.v1.NotificationDeliveryOutcome;
import com.lifeos.notification.config.NotificationProperties;
import com.lifeos.notification.messaging.NotificationStatusOutboxFactory;
import com.lifeos.notification.persistence.NotificationDeadLetter;
import com.lifeos.notification.persistence.NotificationDeadLetterRepository;
import com.lifeos.notification.persistence.NotificationDelivery;
import com.lifeos.notification.persistence.NotificationDeliveryRepository;
import com.lifeos.notification.persistence.NotificationEndpoint;
import com.lifeos.notification.persistence.NotificationEndpointRepository;
import com.lifeos.notification.persistence.NotificationOutboxEventRepository;
import com.lifeos.notification.persistence.NotificationRecord;
import com.lifeos.notification.persistence.NotificationRecordRepository;
import com.lifeos.notification.security.SensitiveValueDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.transaction.annotation.Transactional;

/** Transaction boundaries around delivery leases and durable result/outbox writes. */
@Service
public class NotificationDeliveryTransactions {

    private final NotificationDeliveryRepository deliveryRepository;
    private final NotificationRecordRepository recordRepository;
    private final NotificationEndpointRepository endpointRepository;
    private final NotificationDeadLetterRepository deadLetterRepository;
    private final NotificationOutboxEventRepository outboxRepository;
    private final NotificationStatusOutboxFactory outboxFactory;
    private final FullJitterRetryPolicy retryPolicy;
    private final NotificationProperties properties;
    private final Clock clock;

    public NotificationDeliveryTransactions(
            NotificationDeliveryRepository deliveryRepository,
            NotificationRecordRepository recordRepository,
            NotificationEndpointRepository endpointRepository,
            NotificationDeadLetterRepository deadLetterRepository,
            NotificationOutboxEventRepository outboxRepository,
            NotificationStatusOutboxFactory outboxFactory,
            @Qualifier("notificationDeliveryRetryPolicy") FullJitterRetryPolicy retryPolicy,
            NotificationProperties properties,
            Clock clock) {
        this.deliveryRepository = deliveryRepository;
        this.recordRepository = recordRepository;
        this.endpointRepository = endpointRepository;
        this.deadLetterRepository = deadLetterRepository;
        this.outboxRepository = outboxRepository;
        this.outboxFactory = outboxFactory;
        this.retryPolicy = retryPolicy;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public List<ClaimedDelivery> claimBatch() {
        Instant now = clock.instant();
        return deliveryRepository.findClaimableForUpdate(now, properties.getDelivery().getBatchSize()).stream()
                .map(delivery -> new ClaimedDelivery(
                        delivery.getId(), delivery.claim(now, properties.getDelivery().getLeaseDuration())))
                .toList();
    }

    /**
     * Applies a provider result only if this worker still owns the lease. Late results from an
     * abandoned worker are ignored, allowing the current owner to preserve exactly one outcome.
     */
    @Transactional
    public DeliveryCompletion complete(ClaimedDelivery claim, ProviderDeliveryResult providerResult) {
        NotificationDelivery delivery = deliveryRepository.findByIdForUpdate(claim.deliveryId()).orElse(null);
        if (delivery == null || !claim.leaseToken().equals(delivery.getLeaseToken())) {
            return null;
        }
        NotificationRecord notification = recordRepository.findById(delivery.getNotificationId()).orElseThrow();
        Instant now = clock.instant();
        return switch (providerResult.outcome()) {
            case DELIVERED -> completeDelivered(delivery, notification, claim, providerResult, now);
            case SKIPPED -> completeSkipped(delivery, notification, claim, providerResult, now);
            case TRANSIENT_FAILURE -> completeTransient(delivery, notification, claim, providerResult, now);
            case PERMANENT_FAILURE -> completeDeadLettered(delivery, notification, claim, providerResult, now);
        };
    }

    private DeliveryCompletion completeDelivered(
            NotificationDelivery delivery,
            NotificationRecord notification,
            ClaimedDelivery claim,
            ProviderDeliveryResult result,
            Instant now) {
        delivery.markDelivered(claim.leaseToken(), result.providerMessageId(), now);
        persistStatus(delivery, notification, NotificationDeliveryOutcome.DELIVERED, result.reasonCode(), now);
        return new DeliveryCompletion(delivery.getChannel(), NotificationDeliveryOutcome.DELIVERED, result.reasonCode());
    }

    private DeliveryCompletion completeSkipped(
            NotificationDelivery delivery,
            NotificationRecord notification,
            ClaimedDelivery claim,
            ProviderDeliveryResult result,
            Instant now) {
        delivery.markSkipped(claim.leaseToken(), result.reasonCode(), now);
        persistStatus(delivery, notification, NotificationDeliveryOutcome.SKIPPED, result.reasonCode(), now);
        return new DeliveryCompletion(delivery.getChannel(), NotificationDeliveryOutcome.SKIPPED, result.reasonCode());
    }

    private DeliveryCompletion completeTransient(
            NotificationDelivery delivery,
            NotificationRecord notification,
            ClaimedDelivery claim,
            ProviderDeliveryResult result,
            Instant now) {
        if (delivery.getAttemptCount() < properties.getDelivery().getMaxAttempts()) {
            delivery.scheduleRetry(
                    claim.leaseToken(), result.reasonCode(), now.plus(retryPolicy.nextDelay(delivery.getAttemptCount())), now);
            persistStatus(delivery, notification, NotificationDeliveryOutcome.RETRY_SCHEDULED, result.reasonCode(), now);
            return new DeliveryCompletion(
                    delivery.getChannel(), NotificationDeliveryOutcome.RETRY_SCHEDULED, result.reasonCode());
        }
        return deadLetter(delivery, notification, claim, "RETRY_EXHAUSTED", false, now);
    }

    private DeliveryCompletion completeDeadLettered(
            NotificationDelivery delivery,
            NotificationRecord notification,
            ClaimedDelivery claim,
            ProviderDeliveryResult result,
            Instant now) {
        return deadLetter(delivery, notification, claim, result.reasonCode(), result.disableEndpoint(), now);
    }

    private DeliveryCompletion deadLetter(
            NotificationDelivery delivery,
            NotificationRecord notification,
            ClaimedDelivery claim,
            String reasonCode,
            boolean disableEndpoint,
            Instant now) {
        delivery.markDeadLettered(claim.leaseToken(), reasonCode, now);
        if (disableEndpoint && delivery.getEndpointId() != null) {
            endpointRepository.findById(delivery.getEndpointId()).ifPresent(endpoint -> endpoint.disable(reasonCode, now));
        }
        if (!deadLetterRepository.existsByDeliveryId(delivery.getId())) {
            deadLetterRepository.save(NotificationDeadLetter.from(
                    delivery,
                    reasonCode,
                    SensitiveValueDigest.sha256(
                            delivery.getSourceEventId() + ":" + delivery.getId() + ":" + delivery.getChannel()),
                    now));
        }
        persistStatus(delivery, notification, NotificationDeliveryOutcome.DEAD_LETTERED, reasonCode, now);
        return new DeliveryCompletion(delivery.getChannel(), NotificationDeliveryOutcome.DEAD_LETTERED, reasonCode);
    }

    private void persistStatus(
            NotificationDelivery delivery,
            NotificationRecord notification,
            NotificationDeliveryOutcome outcome,
            String reasonCode,
            Instant now) {
        outboxRepository.save(outboxFactory.create(delivery, notification, outcome, reasonCode, now));
    }
}
