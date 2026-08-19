package com.lifeos.media.domain;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

/** Owner-indexed live-session scheduling access. */
public interface MediaSessionRepository extends JpaRepository<MediaSession, UUID> {

    List<MediaSession> findByTenantIdAndOwnerAccountIdOrderByScheduledStartAtAscIdAsc(
            String tenantId, UUID ownerAccountId, Pageable pageable);

    /** Returns a bounded row-locked batch of sessions whose scheduled end has passed. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("select session from MediaSession session "
            + "where session.status = com.lifeos.media.domain.MediaSessionStatus.SCHEDULED "
            + "and session.scheduledEndAt <= :now order by session.scheduledEndAt asc, session.id asc")
    List<MediaSession> findDueScheduledForUpdate(@Param("now") Instant now, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select session from MediaSession session where session.id = :id")
    Optional<MediaSession> findByIdForUpdate(@Param("id") UUID id);
}
