package com.lifeos.notification.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Idempotency lookup is scoped to the authenticated endpoint owner. */
public interface NotificationEndpointRegistrationIdempotencyRepository
        extends JpaRepository<NotificationEndpointRegistrationIdempotency, UUID> {

    Optional<NotificationEndpointRegistrationIdempotency> findByOwnerAccountIdAndIdempotencyKeyHash(
            UUID ownerAccountId, String idempotencyKeyHash);
}
