package com.lifeos.notification.endpoint;

import com.lifeos.events.v1.NotificationChannel;
import com.lifeos.notification.persistence.NotificationEndpoint;
import com.lifeos.notification.persistence.NotificationEndpointRegistrationIdempotency;
import com.lifeos.notification.persistence.NotificationEndpointRegistrationIdempotencyRepository;
import com.lifeos.notification.persistence.NotificationEndpointRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Atomic reservation, encrypted endpoint write, and completed replay response boundary. */
@Service
public class NotificationEndpointRegistrationTransactions {

    private final NotificationEndpointRegistrationIdempotencyRepository idempotencyRepository;
    private final NotificationEndpointRepository endpointRepository;
    private final Clock clock;

    public NotificationEndpointRegistrationTransactions(
            NotificationEndpointRegistrationIdempotencyRepository idempotencyRepository,
            NotificationEndpointRepository endpointRepository,
            Clock clock) {
        this.idempotencyRepository = idempotencyRepository;
        this.endpointRepository = endpointRepository;
        this.clock = clock;
    }

    @Transactional
    public EndpointRegistrationResult registerFresh(
            UUID ownerAccountId,
            NotificationChannel channel,
            String ciphertext,
            String destinationHash,
            String idempotencyKeyHash,
            String requestFingerprint) {
        Instant now = clock.instant();
        UUID endpointId = UUID.randomUUID();
        idempotencyRepository.saveAndFlush(NotificationEndpointRegistrationIdempotency.pending(
                ownerAccountId, idempotencyKeyHash, requestFingerprint, endpointId, now));
        NotificationEndpoint endpoint = endpointRepository.save(NotificationEndpoint.enabled(
                endpointId, ownerAccountId, channel, ciphertext, destinationHash, now));
        idempotencyRepository.findByOwnerAccountIdAndIdempotencyKeyHash(ownerAccountId, idempotencyKeyHash)
                .orElseThrow()
                .complete(now);
        return new EndpointRegistrationResult(endpoint, false);
    }
}
