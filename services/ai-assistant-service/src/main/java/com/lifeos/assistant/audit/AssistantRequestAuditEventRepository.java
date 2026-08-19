package com.lifeos.assistant.audit;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repository for immutable redacted assistant decision records. */
public interface AssistantRequestAuditEventRepository extends JpaRepository<AssistantRequestAuditEvent, UUID> {
}
