package com.lifeos.documentvault.audit;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repository is deliberately write-only from request paths. */
public interface DocumentVaultSecurityAuditEventRepository
        extends JpaRepository<DocumentVaultSecurityAuditEvent, UUID> {
}
