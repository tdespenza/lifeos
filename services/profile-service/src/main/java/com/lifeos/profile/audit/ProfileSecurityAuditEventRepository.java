package com.lifeos.profile.audit;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repository for redacted Profile security audit records. */
public interface ProfileSecurityAuditEventRepository extends JpaRepository<ProfileSecurityAuditEvent, UUID> {
}
