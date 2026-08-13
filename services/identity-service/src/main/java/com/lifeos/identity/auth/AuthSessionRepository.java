package com.lifeos.identity.auth;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.QueryHints;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * PostgreSQL-backed session persistence operations.
 */
public interface AuthSessionRepository extends JpaRepository<AuthSession, UUID> {

    /**
     * Acquires the target row with a one-second lock deadline so concurrent revocation and refresh
     * work cannot wait indefinitely.
     *
     * @param id session identifier
     * @return locked session when present
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({
        @QueryHint(name = "jakarta.persistence.lock.timeout", value = "1000"),
        @QueryHint(name = "jakarta.persistence.query.timeout", value = "2000")
    })
    @Query("select session from AuthSession session where session.id = :id")
    java.util.Optional<AuthSession> findByIdForUpdate(@Param("id") UUID id);

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

    /**
     * Reads one bounded cursor page owned by an account. A revoked but unexpired row remains
     * visible so the client can display the final revocation state.
     *
     * @param accountId authenticated account
     * @param now current instant
     * @param cursorLastUsedAt last-use cursor component, or null for the first page
     * @param cursorCreatedAt creation cursor component, or null for the first page
     * @param cursorId UUID cursor tie-breaker, or null for the first page
     * @param pageable page size plus one item for next-page detection
     * @return bounded owned session slice
     */
    @Query("select session from AuthSession session "
            + "where session.accountId = :accountId and session.expiresAt > :now "
            + "and (:cursorLastUsedAt is null or session.lastUsedAt < :cursorLastUsedAt "
            + "or (session.lastUsedAt = :cursorLastUsedAt and "
            + "(session.createdAt < :cursorCreatedAt or "
            + "(session.createdAt = :cursorCreatedAt and session.id < :cursorId)))) "
            + "order by session.lastUsedAt desc, session.createdAt desc, session.id desc")
    @QueryHints(@QueryHint(name = "jakarta.persistence.query.timeout", value = "2000"))
    Slice<AuthSession> findOwnedPage(
            @Param("accountId") UUID accountId,
            @Param("now") Instant now,
            @Param("cursorLastUsedAt") Instant cursorLastUsedAt,
            @Param("cursorCreatedAt") Instant cursorCreatedAt,
            @Param("cursorId") UUID cursorId,
            Pageable pageable);

    /**
     * Locks all active sessions except the explicitly authenticated current session.
     *
     * @param accountId authenticated account
     * @param currentSessionId session to preserve
     * @param now current instant
     * @return owned active sessions eligible for revocation
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({
        @QueryHint(name = "jakarta.persistence.lock.timeout", value = "1000"),
        @QueryHint(name = "jakarta.persistence.query.timeout", value = "2000")
    })
    @Query("select session from AuthSession session "
            + "where session.accountId = :accountId and session.id <> :currentSessionId "
            + "and session.revoked = false and session.expiresAt > :now "
            + "order by session.lastUsedAt desc, session.createdAt desc, session.id desc")
    List<AuthSession> findOtherActiveByAccountIdForUpdate(
            @Param("accountId") UUID accountId,
            @Param("currentSessionId") UUID currentSessionId,
            @Param("now") Instant now);

    /**
     * Records a successful use without replacing the session's security state.
     *
     * @param sessionId session identifier
     * @param now successful-use instant
     * @return number of active rows updated
     */
    @Modifying
    @Transactional
    @Query("update AuthSession session set session.lastUsedAt = "
            + "case when session.lastUsedAt < :now then :now else session.lastUsedAt end "
            + "where session.id = :sessionId and session.revoked = false "
            + "and session.expiresAt > :now")
    @QueryHints(@QueryHint(name = "jakarta.persistence.query.timeout", value = "2000"))
    int touchLastUsedAt(@Param("sessionId") UUID sessionId, @Param("now") Instant now);
}
