package com.lifeos.identity.notification;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Durable terminal records for recovery notification relay failures. */
public interface IdentityNotificationOutboxDeadLetterRepository
        extends JpaRepository<IdentityNotificationOutboxDeadLetter, UUID> {
}
