package com.lifeos.identity.auth;

import com.lifeos.identity.account.UserAccountRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Owns account-scoped session listing and monotonic revocation.
 *
 * <p>Every mutation locks the account before session rows. This serializes bulk revocation with
 * session creation, while the repository lock timeout keeps contention bounded. PostgreSQL remains
 * the authority; the Redis cache is populated only after a successful commit.
 */
@Service
public class SessionManagementService {

    private final AuthSessionRepository sessionRepository;
    private final UserAccountRepository accountRepository;
    private final SecurityAuditService auditService;
    private final SessionRevocationCache revocationCache;
    private final Clock clock;

    /**
     * Creates the session-management service.
     *
     * @param sessionRepository durable session authority
     * @param accountRepository account lock boundary
     * @param auditService redacted security audit writer
     * @param revocationCache optional negative-state cache
     */
    @Autowired
    public SessionManagementService(
            AuthSessionRepository sessionRepository,
            UserAccountRepository accountRepository,
            SecurityAuditService auditService,
            SessionRevocationCache revocationCache) {
        this(sessionRepository, accountRepository, auditService, revocationCache, Clock.systemUTC());
    }

    SessionManagementService(
            AuthSessionRepository sessionRepository,
            UserAccountRepository accountRepository,
            SecurityAuditService auditService,
            SessionRevocationCache revocationCache,
            Clock clock) {
        this.sessionRepository = sessionRepository;
        this.accountRepository = accountRepository;
        this.auditService = auditService;
        this.revocationCache = revocationCache;
        this.clock = clock;
    }

    /**
     * Lists only unexpired sessions owned by the authenticated account.
     *
     * @param subject durable authenticated subject
     * @param encodedCursor opaque cursor, or null for the first page
     * @param limit requested page size, already bounded by the controller
     * @return safe session page
     */
    @Transactional(readOnly = true)
    public SessionPage listOwnedSessions(AuthenticatedSubject subject, String encodedCursor, int limit) {
        if (subject == null || subject.accountId() == null || subject.sessionId() == null) {
            throw new AuthenticationFailureException();
        }
        if (limit < 1) {
            throw new SessionRequestValidationException();
        }
        SessionPageCursor cursor = encodedCursor == null
                ? null
                : SessionPageCursor.decode(encodedCursor);
        Instant now = clock.instant();
        Slice<AuthSession> page = sessionRepository.findOwnedPage(
                subject.accountId(),
                now,
                cursor == null ? null : cursor.lastUsedAt(),
                cursor == null ? null : cursor.createdAt(),
                cursor == null ? null : cursor.sessionId(),
                PageRequest.of(0, limit + 1));
        List<AuthSession> rows = page.getContent();
        boolean hasNext = page.hasNext() || rows.size() > limit;
        int contentSize = Math.min(limit, rows.size());
        List<SessionSummary> summaries = rows.subList(0, contentSize).stream()
                .map(session -> SessionSummary.from(session, subject.sessionId()))
                .toList();
        String nextCursor = hasNext && !summaries.isEmpty()
                ? new SessionPageCursor(
                        rows.get(contentSize - 1).getLastUsedAt(),
                        rows.get(contentSize - 1).getCreatedAt(),
                        rows.get(contentSize - 1).getId()).encode()
                : null;
        return new SessionPage(summaries, nextCursor);
    }

    /**
     * Revokes one owned session. Missing or foreign identifiers are deliberate no-ops so this
     * endpoint cannot be used to enumerate another account's sessions.
     *
     * @param subject authenticated subject and current session
     * @param targetSessionId requested target
     * @param clientAddress source used only for the audit digest
     * @return redacted mutation outcome
     */
    @Transactional
    public RevocationOutcome revokeOwnedSession(
            AuthenticatedSubject subject, UUID targetSessionId, String clientAddress) {
        if (subject == null || subject.accountId() == null || targetSessionId == null) {
            throw new AuthenticationFailureException();
        }
        try {
            requireAccountLock(subject.accountId());
            AuthSession target = sessionRepository.findByIdForUpdate(targetSessionId).orElse(null);
            boolean changed = target != null
                    && subject.accountId().equals(target.getAccountId())
                    && !target.isRevoked();
            if (changed) {
                target.revoke();
                sessionRepository.saveAndFlush(target);
                scheduleCachePopulation(target);
            }
            auditService.recordOutcomeWithinCurrentTransaction(
                    SecurityAuditEventType.SESSION_REVOKED,
                    subject.accountId(),
                    clientAddress,
                    changed ? "REVOKED" : "NOOP");
            return new RevocationOutcome(changed ? 1 : 0, false);
        } catch (AuthenticationFailureException exception) {
            throw exception;
        } catch (DataAccessException exception) {
            throw new AuthenticationDependencyUnavailableException(exception);
        }
    }

    /**
     * Atomically revokes all active sessions except the authenticated current session.
     *
     * @param subject authenticated subject and session to preserve
     * @param clientAddress source used only for the audit digest
     * @return redacted mutation outcome
     */
    @Transactional
    public RevocationOutcome revokeOtherSessions(AuthenticatedSubject subject, String clientAddress) {
        if (subject == null || subject.accountId() == null || subject.sessionId() == null) {
            throw new AuthenticationFailureException();
        }
        try {
            requireAccountLock(subject.accountId());
            Instant now = clock.instant();
            List<AuthSession> targets = sessionRepository.findOtherActiveByAccountIdForUpdate(
                    subject.accountId(), subject.sessionId(), now);
            targets.forEach(AuthSession::revoke);
            if (!targets.isEmpty()) {
                sessionRepository.saveAllAndFlush(targets);
                targets.forEach(this::scheduleCachePopulation);
            }
            auditService.recordOutcomeWithinCurrentTransaction(
                    SecurityAuditEventType.SESSION_REVOKED,
                    subject.accountId(),
                    clientAddress,
                    "REVOKE_OTHERS");
            return new RevocationOutcome(targets.size(), true);
        } catch (AuthenticationFailureException exception) {
            throw exception;
        } catch (DataAccessException exception) {
            throw new AuthenticationDependencyUnavailableException(exception);
        }
    }

    private void requireAccountLock(UUID accountId) {
        accountRepository.findByIdForUpdate(accountId)
                .orElseThrow(AuthenticationFailureException::new);
    }

    private void scheduleCachePopulation(AuthSession session) {
        Runnable publish = () -> revocationCache.markRevoked(session.getId(), session.getExpiresAt());
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish.run();
                }
            });
        } else {
            publish.run();
        }
    }

    /** Redacted count/result returned internally; session identifiers never enter the audit row. */
    public record RevocationOutcome(int affectedCount, boolean bulk) {
    }
}
