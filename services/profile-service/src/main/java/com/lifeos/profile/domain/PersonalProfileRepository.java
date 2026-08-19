package com.lifeos.profile.domain;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

/** Owner/tenant constrained repository; no lookup exists for arbitrary public account IDs. */
public interface PersonalProfileRepository extends JpaRepository<PersonalProfile, UUID> {

    Optional<PersonalProfile> findByOwnerAccountIdAndTenantId(UUID ownerAccountId, String tenantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("select profile from PersonalProfile profile "
            + "where profile.ownerAccountId = :ownerAccountId and profile.tenantId = :tenantId")
    Optional<PersonalProfile> findByOwnerAccountIdAndTenantIdForUpdate(
            @Param("ownerAccountId") UUID ownerAccountId, @Param("tenantId") String tenantId);
}
