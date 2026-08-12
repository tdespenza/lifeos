package com.lifeos.identity.auth;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Atomic idempotency-record operations for refresh rotation. */
public interface RefreshReplayRecordRepository extends JpaRepository<RefreshReplayRecord, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select record from RefreshReplayRecord record where record.familyId = :familyId "
            + "and record.idempotencyKey = :idempotencyKey")
    Optional<RefreshReplayRecord> findByFamilyIdAndIdempotencyKeyForUpdate(
            @Param("familyId") UUID familyId,
            @Param("idempotencyKey") String idempotencyKey);
}
