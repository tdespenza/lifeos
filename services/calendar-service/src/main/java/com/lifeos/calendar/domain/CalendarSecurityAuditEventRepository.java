package com.lifeos.calendar.domain;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Redacted Calendar audit persistence. */
public interface CalendarSecurityAuditEventRepository extends JpaRepository<CalendarSecurityAuditEvent, UUID> {
}
