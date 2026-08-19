package com.lifeos.finance.audit;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Finance-owned immutable security audit storage. */
public interface FinanceSecurityAuditEventRepository extends JpaRepository<FinanceSecurityAuditEvent, UUID> {
}
