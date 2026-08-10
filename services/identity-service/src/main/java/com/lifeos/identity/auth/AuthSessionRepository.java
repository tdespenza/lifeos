package com.lifeos.identity.auth;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * PostgreSQL-backed session persistence operations.
 */
public interface AuthSessionRepository extends JpaRepository<AuthSession, UUID> {

    /**
     * Counts sessions which are not revoked and have not expired.
     *
     * @param accountId account UUID
     * @param now comparison instant
     * @return number of active sessions
     */
    @Query("select count(session) from AuthSession session "
            + "where session.accountId = :accountId and session.revoked = false "
            + "and session.expiresAt > :now")
    long countActiveByAccountId(@Param("accountId") UUID accountId, @Param("now") Instant now);
}
