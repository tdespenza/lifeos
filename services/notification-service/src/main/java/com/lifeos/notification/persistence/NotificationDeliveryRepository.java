package com.lifeos.notification.persistence;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Work-queue queries use PostgreSQL's {@code SKIP LOCKED} so horizontally scaled workers neither
 * block each other nor retain locks during provider calls. H2's PostgreSQL compatibility mode is
 * used in migration/integration tests; provider workers are exercised through contractable fakes.
 */
public interface NotificationDeliveryRepository extends JpaRepository<NotificationDelivery, UUID> {

    @Query(
            value = """
                    select * from notification_delivery
                    where (state in ('PENDING', 'RETRY_SCHEDULED') and next_attempt_at <= :now)
                       or (state = 'IN_FLIGHT' and lease_expires_at <= :now)
                    order by next_attempt_at asc, created_at asc
                    for update skip locked
                    limit :limit
                    """,
            nativeQuery = true)
    List<NotificationDelivery> findClaimableForUpdate(@Param("now") Instant now, @Param("limit") int limit);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select delivery from NotificationDelivery delivery where delivery.id = :id")
    Optional<NotificationDelivery> findByIdForUpdate(@Param("id") UUID id);
}
