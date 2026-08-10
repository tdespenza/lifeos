package com.lifeos.identity.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lifeos.identity.account.UserAccount;
import com.lifeos.identity.account.UserAccountRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit tests for the first-party login orchestration and generic failure contract.
 */
@ExtendWith(MockitoExtension.class)
class LoginServiceTest {

    @Mock
    private UserAccountRepository accountRepository;

    @Mock
    private PasswordCredentialRepository credentialRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private LoginRateLimiter rateLimiter;

    @Mock
    private SessionTokenAuthority sessionTokenAuthority;

    @Mock
    private SecurityAuditService auditService;

    @Mock
    private LoginMetrics metrics;

    @Mock
    private PasswordVerifier passwordVerifier;

    private LoginService service;

    @BeforeEach
    void setUp() {
        when(passwordEncoder.encode(anyString())).thenReturn("dummy-hash");
        service = new LoginService(
                accountRepository,
                credentialRepository,
                passwordEncoder,
                rateLimiter,
                sessionTokenAuthority,
                auditService,
                metrics,
                passwordVerifier);
    }

    @Test
    void authenticatesActiveAccountAndCreatesSharedSession() {
        UserAccount account = account();
        PasswordCredential credential = new PasswordCredential(account, "argon2-hash");
        LoginResponse expected = new LoginResponse(UUID.randomUUID(), "signed-token", "Bearer", 300);
        when(accountRepository.findByEmail("ada@example.com")).thenReturn(Optional.of(account));
        when(credentialRepository.findByAccountId(account.getId())).thenReturn(Optional.of(credential));
        when(passwordVerifier.matches("correct", "argon2-hash")).thenReturn(true);
        when(sessionTokenAuthority.createSession(account)).thenReturn(expected);

        LoginResponse actual = service.login(new LoginRequest(" ADA@EXAMPLE.COM ", "correct"), "127.0.0.1");

        assertThat(actual).isEqualTo(expected);
        verify(sessionTokenAuthority).createSession(account);
        verify(auditService).recordWithinCurrentTransaction(
                SecurityAuditEventType.LOGIN_SUCCEEDED, account.getId(), "127.0.0.1");
        verify(metrics).record(SecurityAuditEventType.LOGIN_SUCCEEDED);
    }

    @Test
    void rejectsUnknownEmailWithSameGenericFailureAndDummyHashVerification() {
        when(accountRepository.findByEmail("unknown@example.com")).thenReturn(Optional.empty());
        when(passwordVerifier.matches("secret", "dummy-hash")).thenReturn(false);

        assertThatThrownBy(() -> service.login(
                new LoginRequest("unknown@example.com", "secret"), "127.0.0.1"))
                .isInstanceOf(AuthenticationFailureException.class)
                .hasMessage("The supplied credentials could not be verified.");

        verify(passwordVerifier).matches("secret", "dummy-hash");
        verify(sessionTokenAuthority, never()).createSession(any());
        verify(auditService).record(SecurityAuditEventType.LOGIN_FAILED, null, "127.0.0.1");
    }

    @Test
    void rejectsDisabledAccountEvenWhenPasswordMatches() {
        UserAccount account = account();
        account.disable();
        PasswordCredential credential = new PasswordCredential(account, "argon2-hash");
        when(accountRepository.findByEmail("ada@example.com")).thenReturn(Optional.of(account));
        when(credentialRepository.findByAccountId(account.getId())).thenReturn(Optional.of(credential));
        when(passwordVerifier.matches("correct", "argon2-hash")).thenReturn(true);

        assertThatThrownBy(() -> service.login(
                new LoginRequest("ada@example.com", "correct"), "127.0.0.1"))
                .isInstanceOf(AuthenticationFailureException.class)
                .hasMessage("The supplied credentials could not be verified.");

        verify(sessionTokenAuthority, never()).createSession(any());
        verify(auditService).record(SecurityAuditEventType.LOGIN_FAILED, account.getId(), "127.0.0.1");
    }

    @Test
    void convertsRateLimiterRejectionToAnAuditedFailureWithoutCredentialLookup() {
        doThrow(new LoginRateLimitExceededException(60)).when(rateLimiter)
                .check(anyString(), anyString());

        assertThatThrownBy(() -> service.login(
                new LoginRequest("ada@example.com", "correct"), "127.0.0.1"))
                .isInstanceOf(LoginRateLimitExceededException.class);

        verify(accountRepository, never()).findByEmail(anyString());
        verify(auditService).record(SecurityAuditEventType.LOGIN_RATE_LIMITED, null, "127.0.0.1");
    }

    @Test
    void failsClosedWhenRateLimiterDependencyIsUnavailable() {
        doThrow(new AuthenticationDependencyUnavailableException()).when(rateLimiter)
                .check(anyString(), anyString());

        assertThatThrownBy(() -> service.login(
                new LoginRequest("ada@example.com", "correct"), "127.0.0.1"))
                .isInstanceOf(AuthenticationDependencyUnavailableException.class);

        verify(accountRepository, never()).findByEmail(anyString());
        verify(auditService).record(SecurityAuditEventType.LOGIN_DEPENDENCY_UNAVAILABLE, null, "127.0.0.1");
    }

    @Test
    void auditsAndFailsClosedWhenLocalHashingCapacityIsUnavailable() {
        UserAccount account = account();
        PasswordCredential credential = new PasswordCredential(account, "argon2-hash");
        when(accountRepository.findByEmail("ada@example.com")).thenReturn(Optional.of(account));
        when(credentialRepository.findByAccountId(account.getId())).thenReturn(Optional.of(credential));
        doThrow(new AuthenticationDependencyUnavailableException()).when(passwordVerifier)
                .matches("correct", "argon2-hash");

        assertThatThrownBy(() -> service.login(
                new LoginRequest("ada@example.com", "correct"), "127.0.0.1"))
                .isInstanceOf(AuthenticationDependencyUnavailableException.class);

        verify(sessionTokenAuthority, never()).createSession(any());
        verify(auditService).record(SecurityAuditEventType.LOGIN_DEPENDENCY_UNAVAILABLE,
                account.getId(), "127.0.0.1");
    }

    @Test
    void mapsSessionPersistenceFailureToSanitizedDependencyFailure() {
        UserAccount account = account();
        PasswordCredential credential = new PasswordCredential(account, "argon2-hash");
        when(accountRepository.findByEmail("ada@example.com")).thenReturn(Optional.of(account));
        when(credentialRepository.findByAccountId(account.getId())).thenReturn(Optional.of(credential));
        when(passwordVerifier.matches("correct", "argon2-hash")).thenReturn(true);
        doThrow(new DataIntegrityViolationException("session persistence failed"))
                .when(sessionTokenAuthority).createSession(account);

        assertThatThrownBy(() -> service.login(
                new LoginRequest("ada@example.com", "correct"), "127.0.0.1"))
                .isInstanceOf(AuthenticationDependencyUnavailableException.class)
                .hasMessage("Authentication is temporarily unavailable.");

        verify(auditService).record(SecurityAuditEventType.LOGIN_DEPENDENCY_UNAVAILABLE,
                account.getId(), "127.0.0.1");
        verify(auditService, never()).recordWithinCurrentTransaction(
                SecurityAuditEventType.LOGIN_SUCCEEDED, account.getId(), "127.0.0.1");
        verify(metrics, never()).record(SecurityAuditEventType.LOGIN_SUCCEEDED);
    }

    @Test
    void auditsLockedAccountRevalidationFailureAsGenericLoginFailure() {
        UserAccount account = account();
        PasswordCredential credential = new PasswordCredential(account, "argon2-hash");
        when(accountRepository.findByEmail("ada@example.com")).thenReturn(Optional.of(account));
        when(credentialRepository.findByAccountId(account.getId())).thenReturn(Optional.of(credential));
        when(passwordVerifier.matches("correct", "argon2-hash")).thenReturn(true);
        doThrow(new AuthenticationFailureException()).when(sessionTokenAuthority).createSession(account);

        assertThatThrownBy(() -> service.login(
                new LoginRequest("ada@example.com", "correct"), "127.0.0.1"))
                .isInstanceOf(AuthenticationFailureException.class)
                .hasMessage("The supplied credentials could not be verified.");

        verify(auditService).record(SecurityAuditEventType.LOGIN_FAILED,
                account.getId(), "127.0.0.1");
        verify(metrics, never()).record(SecurityAuditEventType.LOGIN_SUCCEEDED);
    }

    private UserAccount account() {
        UserAccount account = new UserAccount("ada@example.com", "Ada Lovelace");
        ReflectionTestUtils.setField(account, "id", UUID.randomUUID());
        return account;
    }
}
