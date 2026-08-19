package com.lifeos.notification.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Durable terminal delivery diagnostics, deliberately stripped of provider response/body data. */
public interface NotificationDeadLetterRepository extends JpaRepository<NotificationDeadLetter, UUID> {

    boolean existsByDeliveryId(UUID deliveryId);
}
