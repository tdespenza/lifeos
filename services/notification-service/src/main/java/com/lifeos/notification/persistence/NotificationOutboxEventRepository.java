package com.lifeos.notification.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Polling relay query for immutable outbox records. */
public interface NotificationOutboxEventRepository extends JpaRepository<NotificationOutboxEvent, UUID> {

    @Query(
            value = """
                    select * from notification_outbox_event
                    where (state = 'PENDING' and available_at <= :now)
                       or (state = 'IN_FLIGHT' and lease_expires_at <= :now)
                    order by available_at asc, created_at asc
                    for update skip locked
                    limit :limit
                    """,
            nativeQuery = true)
    List<NotificationOutboxEvent> findClaimableForUpdate(@Param("now") Instant now, @Param("limit") int limit);
}
