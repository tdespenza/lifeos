package com.lifeos.documentvault.domain;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

/** Every caller-facing read carries owner and tenant predicates; no document lookup is global. */
public interface VaultDocumentRepository extends JpaRepository<VaultDocument, UUID> {

    Optional<VaultDocument> findByIdAndOwnerAccountIdAndTenantId(UUID id, UUID ownerAccountId, String tenantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("select document from VaultDocument document where document.id = :id "
            + "and document.ownerAccountId = :ownerAccountId and document.tenantId = :tenantId")
    Optional<VaultDocument> findByIdAndOwnerScopeForUpdate(
            @Param("id") UUID id,
            @Param("ownerAccountId") UUID ownerAccountId,
            @Param("tenantId") String tenantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select document from VaultDocument document where document.id = :id")
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    Optional<VaultDocument> findByIdForUpdate(@Param("id") UUID id);

    /**
     * The service reads only a fixed leading owner catalog window, then filters/ranks in memory.
     * The supporting owner/tenant/updated index lets the database stop after that fixed window.
     */
    List<VaultDocument> findByOwnerAccountIdAndTenantIdOrderByUpdatedAtDescIdAsc(
            UUID ownerAccountId, String tenantId, Pageable pageable);
}
