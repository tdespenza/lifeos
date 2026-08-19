package com.lifeos.trustledger.proof;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TrustAiAuditHashRequestRepository extends JpaRepository<TrustAiAuditHashRequest, UUID> {
}
