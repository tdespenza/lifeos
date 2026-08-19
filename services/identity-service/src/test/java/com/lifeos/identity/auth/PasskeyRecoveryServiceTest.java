package com.lifeos.identity.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lifeos.identity.account.UserAccount;
import com.lifeos.identity.account.UserAccountRepository;
import com.lifeos.identity.notification.IdentityRecoveryNotificationService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PasskeyRecoveryServiceTest {

    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final String SECRET = "test-passkey-recovery-secret-at-least-32-bytes";
    private static final AuthenticatedSubject SUBJECT =
            new AuthenticatedSubject(ACCOUNT_ID, UUID.randomUUID(), "PASSWORD", "proof");

    @Mock
    private UserAccountRepository accountRepository;

    @Mock
    private PasskeyRecoveryCodeRepository codeRepository;

    @Mock
    private LoginRateLimiter rateLimiter;

    @Mock
    private SessionTokenAuthority sessionTokenAuthority;

    @Mock
    private SecurityAuditService auditService;

    @Mock
    private IdentityRecoveryNotificationService notificationService;

    private PasskeyRecoveryService service;

    @BeforeEach
    void setUp() {
        PasskeyRecoveryProperties properties = new PasskeyRecoveryProperties();
        properties.setHmacSecret(SECRET);
        properties.setCodeTtl(java.time.Duration.ofMinutes(15));
        properties.setCodeCount(4);
        service = new PasskeyRecoveryService(
                properties,
                accountRepository,
                codeRepository,
                rateLimiter,
                sessionTokenAuthority,
                auditService,
                notificationService,
                Clock.fixed(NOW, ZoneOffset.UTC),
                new java.security.SecureRandom());
    }

    @Test
    void generatesBoundedOneTimeCodesAndAuditsIssuance() {
        UserAccount account = mock(UserAccount.class);
        when(account.getId()).thenReturn(ACCOUNT_ID);
        when(account.isActive()).thenReturn(true);
        when(accountRepository.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(codeRepository.findAllByAccount_IdAndUsedAtIsNull(ACCOUNT_ID)).thenReturn(java.util.List.of());

        PasskeyRecoveryResult result = service.generate(SUBJECT, "127.0.0.1");

        assertThat(result.codes()).hasSize(4)
                .allMatch(code -> code.matches("[A-Z2-7]{4}(?:-[A-Z2-7]{4}){2}"));
        assertThat(result.expiresAt()).isEqualTo(NOW.plusSeconds(900));
        verify(codeRepository).flush();
        verify(codeRepository, org.mockito.Mockito.times(4)).save(any(PasskeyRecoveryCode.class));
        verify(auditService).recordWithinCurrentTransaction(
                SecurityAuditEventType.PASSKEY_RECOVERY_CODES_ISSUED, ACCOUNT_ID, "127.0.0.1");
        verify(notificationService).enqueueCodesIssued(ACCOUNT_ID);
    }

    @Test
    void consumesAValidCodeExactlyOnceAndCreatesPasskeySession() {
        String code = "ABCD-EFGH-JKLM";
        UserAccount account = mock(UserAccount.class);
        when(account.getId()).thenReturn(ACCOUNT_ID);
        when(account.isActive()).thenReturn(true);
        PasskeyRecoveryCode recoveryCode = new PasskeyRecoveryCode(
                account,
                new HmacSha256Digest(SECRET, "test").digest(code),
                NOW.minusSeconds(10),
                NOW.plusSeconds(900));
        when(accountRepository.findByEmail("ada@example.com")).thenReturn(Optional.of(account));
        when(codeRepository.findUsableForUpdate(ACCOUNT_ID, recoveryCode.getCodeHash()))
                .thenReturn(Optional.of(recoveryCode));
        LoginResponse expected = new LoginResponse(UUID.randomUUID(), "access", "Bearer", 300);
        when(sessionTokenAuthority.createSession(
                account, SessionAuthenticationMethod.PASSKEY, DeviceMetadata.unknown())).thenReturn(expected);

        LoginResponse result = service.recover(
                new PasskeyRecoveryRequest("ada@example.com", code), "127.0.0.1", DeviceMetadata.unknown());

        assertThat(result).isSameAs(expected);
        assertThat(recoveryCode.getUsedAt()).isEqualTo(NOW);
        verify(codeRepository).saveAndFlush(recoveryCode);
        verify(auditService).recordWithinCurrentTransaction(
                SecurityAuditEventType.PASSKEY_RECOVERY_SUCCEEDED, ACCOUNT_ID, "127.0.0.1");
        verify(notificationService).enqueueRecoverySucceeded(ACCOUNT_ID);
    }

    @Test
    void rejectsUnknownCodeWithoutCreatingASession() {
        UserAccount account = mock(UserAccount.class);
        when(account.getId()).thenReturn(ACCOUNT_ID);
        when(account.isActive()).thenReturn(true);
        when(accountRepository.findByEmail("ada@example.com")).thenReturn(Optional.of(account));
        when(codeRepository.findUsableForUpdate(any(), anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.recover(
                new PasskeyRecoveryRequest("ada@example.com", "ABCD-EFGH-JKLM"),
                "127.0.0.1",
                DeviceMetadata.unknown()))
                .isInstanceOf(AuthenticationFailureException.class);

        verify(sessionTokenAuthority, org.mockito.Mockito.never()).createSession(
                any(), any(), any());
        verify(auditService).record(SecurityAuditEventType.PASSKEY_RECOVERY_REJECTED, ACCOUNT_ID, "127.0.0.1");
    }
}
