package com.lifeos.documentvault.proof;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface DocumentProofRequestRepository extends JpaRepository<DocumentProofRequest, UUID> {

    Optional<DocumentProofRequest> findByOwnerAccountIdAndTenantIdAndIdempotencyKeyHash(
            UUID ownerAccountId, String tenantId, String idempotencyKeyHash);

    @Lock(LockModeType.PESSIMISTIC_READ)
    Optional<DocumentProofRequest> findByIdAndOwnerAccountIdAndTenantId(
            @Param("id") UUID id, @Param("ownerAccountId") UUID ownerAccountId, @Param("tenantId") String tenantId);
}
