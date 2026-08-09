package com.lifeos.identity.auth;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence operations for security audit events.
 */
public interface SecurityAuditEventRepository extends JpaRepository<SecurityAuditEvent, UUID> {
}
