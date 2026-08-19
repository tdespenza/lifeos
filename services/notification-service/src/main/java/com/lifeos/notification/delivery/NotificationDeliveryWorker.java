package com.lifeos.notification.delivery;

import com.lifeos.events.v1.NotificationChannel;
import com.lifeos.notification.persistence.NotificationDelivery;
import com.lifeos.notification.persistence.NotificationDeliveryRepository;
import com.lifeos.notification.persistence.NotificationEndpoint;
import com.lifeos.notification.persistence.NotificationEndpointRepository;
import com.lifeos.notification.persistence.NotificationRecord;
import com.lifeos.notification.persistence.NotificationRecordRepository;
import com.lifeos.notification.security.EndpointCipher;
import com.lifeos.notification.security.EndpointCipherException;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Bounded poller that makes provider calls outside database transactions. Individual channel work
 * items mean an unavailable email provider cannot prevent push or realtime delivery.
 */
@Component
@ConditionalOnProperty(value = "notification.delivery.worker-enabled", havingValue = "true", matchIfMissing = true)
public class NotificationDeliveryWorker {

    private final NotificationDeliveryTransactions transactions;
    private final NotificationDeliveryRepository deliveryRepository;
    private final NotificationRecordRepository recordRepository;
    private final NotificationEndpointRepository endpointRepository;
    private final EndpointCipher endpointCipher;
    private final Map<NotificationChannel, NotificationProvider> providers;
    private final NotificationMetrics metrics;
    private final Clock clock;

    public NotificationDeliveryWorker(
            NotificationDeliveryTransactions transactions,
            NotificationDeliveryRepository deliveryRepository,
            NotificationRecordRepository recordRepository,
            NotificationEndpointRepository endpointRepository,
            EndpointCipher endpointCipher,
            List<NotificationProvider> providerList,
            NotificationMetrics metrics,
            Clock clock) {
        this.transactions = transactions;
        this.deliveryRepository = deliveryRepository;
        this.recordRepository = recordRepository;
        this.endpointRepository = endpointRepository;
        this.endpointCipher = endpointCipher;
        this.metrics = metrics;
        this.clock = clock;
        this.providers = toProviderMap(providerList);
    }

    @Scheduled(fixedDelayString = "${notification.delivery.poll-delay:1s}")
    public void dispatchDueDeliveries() {
        transactions.claimBatch().forEach(this::dispatch);
    }

    private void dispatch(ClaimedDelivery claim) {
        ProviderDeliveryResult result;
        try {
            result = attempt(claim);
        } catch (RuntimeException exception) {
            result = ProviderDeliveryResult.transientFailure("DELIVERY_WORKER_FAILURE");
        }
        DeliveryCompletion completion = transactions.complete(claim, result);
        if (completion != null) {
            metrics.recordDelivery(completion);
        }
    }

    private ProviderDeliveryResult attempt(ClaimedDelivery claim) {
        NotificationDelivery delivery = deliveryRepository.findById(claim.deliveryId()).orElse(null);
        if (delivery == null || !claim.leaseToken().equals(delivery.getLeaseToken())) {
            return ProviderDeliveryResult.skipped("LEASE_LOST");
        }
        NotificationRecord notification = recordRepository.findById(delivery.getNotificationId()).orElse(null);
        if (notification == null) {
            return ProviderDeliveryResult.permanentFailure("NOTIFICATION_NOT_FOUND", false);
        }
        Instant now = clock.instant();
        if (notification.getExpiresAt() != null && !notification.getExpiresAt().isAfter(now)) {
            return ProviderDeliveryResult.skipped("NOTIFICATION_EXPIRED");
        }
        String destination;
        try {
            destination = destination(delivery);
        } catch (EndpointCipherException exception) {
            return ProviderDeliveryResult.permanentFailure("ENDPOINT_DECRYPTION_FAILED", false);
        }
        if (delivery.getChannel() != NotificationChannel.REALTIME && destination == null) {
            return ProviderDeliveryResult.skipped("NO_ENABLED_ENDPOINT");
        }
        NotificationProvider provider = providers.get(delivery.getChannel());
        if (provider == null) {
            return ProviderDeliveryResult.permanentFailure("PROVIDER_NOT_CONFIGURED", false);
        }
        return provider.deliver(new ProviderDeliveryRequest(
                delivery.getId(),
                delivery.getNotificationId(),
                delivery.getSourceEventId(),
                delivery.getRecipientAccountId(),
                delivery.getChannel(),
                destination,
                notification.getSequenceNumber(),
                notification.getCategory(),
                notification.getPriority(),
                notification.getTitle(),
                notification.getBody(),
                notification.getActionUri(),
                notification.getCreatedAt(),
                notification.getExpiresAt()));
    }

    private String destination(NotificationDelivery delivery) {
        if (delivery.getChannel() == NotificationChannel.REALTIME) {
            return null;
        }
        if (delivery.getEndpointId() == null) {
            return null;
        }
        NotificationEndpoint endpoint = endpointRepository.findById(delivery.getEndpointId()).orElse(null);
        if (endpoint == null || !endpoint.isEnabled() || endpoint.getChannel() != delivery.getChannel()) {
            return null;
        }
        return endpointCipher.decrypt(endpoint.getDestinationCiphertext());
    }

    private static Map<NotificationChannel, NotificationProvider> toProviderMap(List<NotificationProvider> providerList) {
        Map<NotificationChannel, NotificationProvider> mapped = new EnumMap<>(NotificationChannel.class);
        for (NotificationProvider provider : providerList) {
            if (mapped.put(provider.channel(), provider) != null) {
                throw new IllegalArgumentException("more than one notification provider configured for a channel");
            }
        }
        return Map.copyOf(mapped);
    }
}
