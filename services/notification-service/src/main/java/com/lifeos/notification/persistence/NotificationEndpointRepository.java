package com.lifeos.notification.persistence;

import com.lifeos.events.v1.NotificationChannel;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Endpoint lookups never expose a raw destination outside the notification service. */
public interface NotificationEndpointRepository extends JpaRepository<NotificationEndpoint, UUID> {

    List<NotificationEndpoint> findByOwnerAccountIdAndChannelAndEnabledTrue(
            UUID ownerAccountId, NotificationChannel channel);

    Optional<NotificationEndpoint> findByIdAndOwnerAccountId(UUID id, UUID ownerAccountId);

    Optional<NotificationEndpoint> findByOwnerAccountIdAndChannelAndDestinationHash(
            UUID ownerAccountId, NotificationChannel channel, String destinationHash);

    List<NotificationEndpoint> findByOwnerAccountIdOrderByCreatedAtAsc(UUID ownerAccountId);
}
