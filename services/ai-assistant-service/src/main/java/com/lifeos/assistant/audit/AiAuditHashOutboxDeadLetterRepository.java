package com.lifeos.assistant.audit;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiAuditHashOutboxDeadLetterRepository
        extends JpaRepository<AiAuditHashOutboxDeadLetter, UUID> {
}
