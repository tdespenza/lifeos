package com.lifeos.documentvault.proof;

import java.util.UUID;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DocumentProofOutboxEventRepository extends JpaRepository<DocumentProofOutboxEvent, UUID> {

    @Query(value = """
            select * from document_proof_outbox_event
            where published_at is null
              and dead_lettered_at is null
              and next_attempt_at <= :now
              and (lease_until is null or lease_until < :now)
            order by next_attempt_at asc, created_at asc, id asc
            limit :limit for update skip locked
            """, nativeQuery = true)
    List<DocumentProofOutboxEvent> findClaimableForUpdate(@Param("now") Instant now, @Param("limit") int limit);
}
