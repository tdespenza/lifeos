package com.lifeos.notification.persistence;

import java.util.UUID;
import java.time.Instant;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Repository for durable CloudEvents dedupe rows. */
public interface NotificationInboxEventRepository extends JpaRepository<NotificationInboxEvent, UUID> {

    /**
     * Uses an INSERT rather than JpaRepository.save: an assigned CloudEvents ID otherwise takes
     * JPA's merge path and can overwrite an existing inbox row instead of triggering the primary
     * key conflict that drives durable duplicate handling.
     */
    @Modifying
    @Query(
            value = """
                    insert into notification_inbox_event
                        (event_id, source, event_type, correlation_id, payload_hash, state, received_at)
                    values
                        (:eventId, :source, :eventType, :correlationId, :payloadHash, 'RECEIVED', :receivedAt)
                    """,
            nativeQuery = true)
    int reserve(
            @Param("eventId") UUID eventId,
            @Param("source") String source,
            @Param("eventType") String eventType,
            @Param("correlationId") UUID correlationId,
            @Param("payloadHash") String payloadHash,
            @Param("receivedAt") Instant receivedAt);
}
