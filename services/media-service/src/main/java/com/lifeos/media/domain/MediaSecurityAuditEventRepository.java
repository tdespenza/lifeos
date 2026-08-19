package com.lifeos.media.domain;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Redacted durable Media audit persistence. */
public interface MediaSecurityAuditEventRepository extends JpaRepository<MediaSecurityAuditEvent, UUID> {
}
