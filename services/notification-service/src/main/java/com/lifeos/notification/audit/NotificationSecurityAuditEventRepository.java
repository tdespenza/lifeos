package com.lifeos.notification.audit;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repository for redacted durable notification security facts. */
public interface NotificationSecurityAuditEventRepository extends JpaRepository<NotificationSecurityAuditEvent, UUID> {

    long countByEventTypeAndOutcome(String eventType, NotificationSecurityAuditOutcome outcome);
}
