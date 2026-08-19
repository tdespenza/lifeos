package com.lifeos.trustledger.anchor;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TrustDigestAnchorRequestRepository extends JpaRepository<TrustDigestAnchorRequest, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<TrustDigestAnchorRequest> findByOwnerAccountIdAndTenantIdAndSubjectTypeAndSubjectIdAndSubjectVersionAndIdempotencyKeyHash(
            UUID ownerAccountId,
            String tenantId,
            String subjectType,
            UUID subjectId,
            long subjectVersion,
            String idempotencyKeyHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<TrustDigestAnchorRequest> findByRequestIdAndOwnerAccountIdAndTenantId(
            UUID requestId, UUID ownerAccountId, String tenantId);

    @Query("""
            select request from TrustDigestAnchorRequest request
            where request.requestId = :requestId
              and request.ownerAccountId = :ownerAccountId
              and request.tenantId = :tenantId
            """)
    Optional<TrustDigestAnchorRequest> findForRead(
            @Param("requestId") UUID requestId,
            @Param("ownerAccountId") UUID ownerAccountId,
            @Param("tenantId") String tenantId);
}
