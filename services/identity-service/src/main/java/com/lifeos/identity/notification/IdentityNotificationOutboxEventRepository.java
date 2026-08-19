package com.lifeos.identity.notification;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Lease-safe access to Identity's recovery notification producer outbox. */
public interface IdentityNotificationOutboxEventRepository
        extends JpaRepository<IdentityNotificationOutboxEvent, UUID> {

    @Query(
            value = """
                    select * from identity_notification_outbox_event
                    where (state = 'PENDING' and available_at <= :now)
                       or (state = 'IN_FLIGHT' and lease_expires_at <= :now)
                    order by available_at asc, id asc
                    limit :limit for update skip locked
                    """,
            nativeQuery = true)
    List<IdentityNotificationOutboxEvent> findClaimableForUpdate(
            @Param("now") Instant now, @Param("limit") int limit);
}
