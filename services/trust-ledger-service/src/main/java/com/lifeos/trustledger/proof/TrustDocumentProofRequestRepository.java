package com.lifeos.trustledger.proof;

import java.util.UUID;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TrustDocumentProofRequestRepository extends JpaRepository<TrustDocumentProofRequest, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<TrustDocumentProofRequest> findByRequestIdAndOwnerAccountIdAndTenantId(
            UUID requestId, UUID ownerAccountId, String tenantId);

    @Query("""
            select request from TrustDocumentProofRequest request
            where request.requestId = :requestId
              and request.ownerAccountId = :ownerAccountId
              and request.tenantId = :tenantId
            """)
    Optional<TrustDocumentProofRequest> findForRead(
            @Param("requestId") UUID requestId,
            @Param("ownerAccountId") UUID ownerAccountId,
            @Param("tenantId") String tenantId);
}
