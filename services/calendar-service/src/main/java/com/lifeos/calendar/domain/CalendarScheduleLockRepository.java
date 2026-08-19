package com.lifeos.calendar.domain;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Per-owner database lock row repository. */
public interface CalendarScheduleLockRepository extends JpaRepository<CalendarScheduleLock, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select scheduleLock from CalendarScheduleLock scheduleLock where scheduleLock.ownerAccountId = :ownerAccountId")
    Optional<CalendarScheduleLock> findByOwnerAccountIdForUpdate(@Param("ownerAccountId") UUID ownerAccountId);
}
