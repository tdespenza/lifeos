package com.lifeos.identity.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lifeos.identity.account.UserAccount;
import com.lifeos.identity.account.UserAccountRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.SliceImpl;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class SessionManagementServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-13T12:00:00Z");

    @Mock
    private AuthSessionRepository sessionRepository;

    @Mock
    private UserAccountRepository accountRepository;

    @Mock
    private SecurityAuditService auditService;

    @Mock
    private SessionRevocationCache revocationCache;

    private UUID accountId;
    private UUID currentSessionId;
    private AuthenticatedSubject subject;
    private UserAccount account;
    private SessionManagementService service;

    @BeforeEach
    void setUp() {
        accountId = UUID.randomUUID();
        currentSessionId = UUID.randomUUID();
        subject = new AuthenticatedSubject(accountId, currentSessionId, "PASSWORD", "proof");
        account = new UserAccount("ada@example.com", "Ada Lovelace");
        ReflectionTestUtils.setField(account, "id", accountId);
        service = new SessionManagementService(
                sessionRepository,
                accountRepository,
                auditService,
                revocationCache,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void listProjectsOnlyTheOwnedRowsAndReturnsAnOpaqueNextCursor() {
        AuthSession current = session(currentSessionId, NOW.minusSeconds(10), NOW.plusSeconds(300));
        AuthSession other = session(UUID.randomUUID(), NOW.minusSeconds(20), NOW.plusSeconds(300));
        when(sessionRepository.findOwnedPage(
                eq(accountId), eq(NOW), any(), any(), any(), eq(PageRequest.of(0, 3))))
                .thenReturn(new SliceImpl<>(List.of(current, other), PageRequest.of(0, 3), true));

        SessionPage page = service.listOwnedSessions(subject, null, 2);

        assertThat(page.sessions()).hasSize(2);
        assertThat(page.sessions().get(0).current()).isTrue();
        assertThat(page.sessions().get(1).current()).isFalse();
        assertThat(page.nextCursor()).isNotBlank();
        verify(sessionRepository).findOwnedPage(
                eq(accountId), eq(NOW), eq(null), eq(null), eq(null), eq(PageRequest.of(0, 3)));
    }

    @Test
    void repeatedSingleRevokeIsIdempotentAndPublishesOnlyAfterDurableMutation() {
        UUID targetId = UUID.randomUUID();
        AuthSession target = session(targetId, NOW.minusSeconds(10), NOW.plusSeconds(300));
        when(accountRepository.findByIdForUpdate(accountId)).thenReturn(Optional.of(account));
        when(sessionRepository.findByIdForUpdate(targetId)).thenReturn(Optional.of(target));

        SessionManagementService.RevocationOutcome first = service.revokeOwnedSession(subject, targetId, "127.0.0.1");
        SessionManagementService.RevocationOutcome second = service.revokeOwnedSession(subject, targetId, "127.0.0.1");

        assertThat(first.affectedCount()).isEqualTo(1);
        assertThat(second.affectedCount()).isZero();
        verify(sessionRepository).saveAndFlush(target);
        verify(revocationCache).markRevoked(targetId, target.getExpiresAt());
        verify(auditService).recordOutcomeWithinCurrentTransaction(
                SecurityAuditEventType.SESSION_REVOKED, accountId, "127.0.0.1", "REVOKED");
        verify(auditService).recordOutcomeWithinCurrentTransaction(
                SecurityAuditEventType.SESSION_REVOKED, accountId, "127.0.0.1", "NOOP");
    }

    @Test
    void foreignTargetIsAnAuditedNoopAndDoesNotMutateTheRow() {
        UUID targetId = UUID.randomUUID();
        AuthSession foreign = session(targetId, NOW.minusSeconds(10), NOW.plusSeconds(300));
        ReflectionTestUtils.setField(foreign, "accountId", UUID.randomUUID());
        when(accountRepository.findByIdForUpdate(accountId)).thenReturn(Optional.of(account));
        when(sessionRepository.findByIdForUpdate(targetId)).thenReturn(Optional.of(foreign));

        SessionManagementService.RevocationOutcome outcome =
                service.revokeOwnedSession(subject, targetId, "127.0.0.1");

        assertThat(outcome.affectedCount()).isZero();
        assertThat(foreign.isRevoked()).isFalse();
        verify(sessionRepository, never()).saveAndFlush(any());
    }

    @Test
    void bulkRevokePreservesTheCurrentSessionAndAuditsOneOutcome() {
        AuthSession other = session(UUID.randomUUID(), NOW.minusSeconds(20), NOW.plusSeconds(300));
        when(accountRepository.findByIdForUpdate(accountId)).thenReturn(Optional.of(account));
        when(sessionRepository.findOtherActiveByAccountIdForUpdate(accountId, currentSessionId, NOW))
                .thenReturn(List.of(other));

        SessionManagementService.RevocationOutcome outcome =
                service.revokeOtherSessions(subject, "127.0.0.1");

        assertThat(outcome.affectedCount()).isEqualTo(1);
        assertThat(outcome.bulk()).isTrue();
        assertThat(other.isRevoked()).isTrue();
        verify(sessionRepository).saveAllAndFlush(List.of(other));
        verify(auditService).recordOutcomeWithinCurrentTransaction(
                SecurityAuditEventType.SESSION_REVOKED, accountId, "127.0.0.1", "REVOKE_OTHERS");
    }

    private AuthSession session(UUID sessionId, Instant createdAt, Instant expiresAt) {
        return new AuthSession(
                sessionId,
                account,
                SessionAuthenticationMethod.PASSWORD,
                TokenDigest.sha256("token-" + sessionId),
                createdAt,
                expiresAt,
                new DeviceMetadata("macos", "chrome", "unknown"));
    }
}
