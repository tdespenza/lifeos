package com.lifeos.assistant.audit;

import java.util.UUID;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;

/** Repository for the transactional AI audit commitment outbox. */
public interface AiAuditHashOutboxEventRepository extends JpaRepository<AiAuditHashOutboxEvent, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select event from AiAuditHashOutboxEvent event "
            + "where event.publishedAt is null and event.deadLetteredAt is null "
            + "and event.nextAttemptAt <= :now "
            + "and (event.leaseUntil is null or event.leaseUntil < :now) "
            + "order by event.createdAt asc, event.id asc")
    List<AiAuditHashOutboxEvent> findClaimableForUpdate(
            @Param("now") Instant now, org.springframework.data.domain.Pageable pageable);
}
