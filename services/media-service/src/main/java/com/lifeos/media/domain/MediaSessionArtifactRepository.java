package com.lifeos.media.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

/** Owner-scoped durable post-session artifact access. */
public interface MediaSessionArtifactRepository extends JpaRepository<MediaSessionArtifact, UUID> {

    Optional<MediaSessionArtifact> findBySessionIdAndOwnerAccountIdAndTenantId(
            UUID sessionId, UUID ownerAccountId, String tenantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select artifact from MediaSessionArtifact artifact where artifact.sessionId = :sessionId "
            + "and artifact.ownerAccountId = :ownerAccountId and artifact.tenantId = :tenantId")
    Optional<MediaSessionArtifact> findForUpdate(
            @Param("sessionId") UUID sessionId,
            @Param("ownerAccountId") UUID ownerAccountId,
            @Param("tenantId") String tenantId);
}
