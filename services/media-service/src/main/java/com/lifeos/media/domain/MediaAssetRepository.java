package com.lifeos.media.domain;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Owner-indexed source asset access; raw storage references never appear in caller predicates. */
public interface MediaAssetRepository extends JpaRepository<MediaAsset, UUID> {

    List<MediaAsset> findByTenantIdAndOwnerAccountIdOrderByCreatedAtDescIdDesc(
            String tenantId, UUID ownerAccountId, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select asset from MediaAsset asset where asset.id = :id")
    Optional<MediaAsset> findByIdForUpdate(@Param("id") UUID id);
}
