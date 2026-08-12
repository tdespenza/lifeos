package com.lifeos.identity.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lifeos.identity.account.UserAccount;
import com.lifeos.identity.account.UserAccountRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Verifies that session creation revalidates mutable authentication state under its locks.
 */
@ExtendWith(MockitoExtension.class)
class JwtSessionTokenAuthorityTest {

    @Mock
    private JwtEncoder jwtEncoder;

    @Mock
    private AuthSessionRepository sessionRepository;

    @Mock
    private UserAccountRepository accountRepository;

    @Mock
    private PasswordCredentialRepository credentialRepository;

    private JwtSessionTokenAuthority authority;

    @BeforeEach
    void setUp() {
        IdentityAuthProperties properties = new IdentityAuthProperties();
        properties.getJwt().setSigningSecret("test-only-secret-that-is-at-least-32-bytes-long");
        authority = new JwtSessionTokenAuthority(
                jwtEncoder,
                sessionRepository,
                accountRepository,
                credentialRepository,
                properties,
                Clock.fixed(Instant.parse("2026-08-09T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void rejectsAccountDisabledBeforeSessionLockCompletes() {
        UserAccount requestedAccount = account();
        UserAccount lockedAccount = accountWithId(requestedAccount.getId());
        lockedAccount.disable();
        when(accountRepository.findByIdForUpdate(requestedAccount.getId()))
                .thenReturn(Optional.of(lockedAccount));

        assertThatThrownBy(() -> authority.createSession(requestedAccount))
                .isInstanceOf(AuthenticationFailureException.class);

        verify(sessionRepository, never()).countActiveByAccountId(any(), any());
        verify(jwtEncoder, never()).encode(any());
        verify(sessionRepository, never()).saveAndFlush(any());
    }

    @Test
    void rejectsCredentialDisabledBeforeSessionLockCompletes() {
        UserAccount requestedAccount = account();
        PasswordCredential credential = new PasswordCredential(requestedAccount, "argon2-hash");
        credential.disable();
        when(accountRepository.findByIdForUpdate(requestedAccount.getId()))
                .thenReturn(Optional.of(requestedAccount));
        when(credentialRepository.findByAccountIdForUpdate(requestedAccount.getId()))
                .thenReturn(Optional.of(credential));

        assertThatThrownBy(() -> authority.createSession(requestedAccount))
                .isInstanceOf(AuthenticationFailureException.class);

        verify(sessionRepository, never()).countActiveByAccountId(any(), any());
        verify(jwtEncoder, never()).encode(any());
        verify(sessionRepository, never()).saveAndFlush(any());
    }

    @Test
    void oidcSessionDoesNotRequireLocalPasswordCredential() {
        UserAccount account = account();
        when(accountRepository.findByIdForUpdate(account.getId())).thenReturn(Optional.of(account));
        when(jwtEncoder.encode(any())).thenReturn(Jwt.withTokenValue("signed-token")
                .header("alg", "HS256")
                .claim("sub", account.getId().toString())
                .claim("session_id", UUID.randomUUID().toString())
                .issuedAt(Instant.parse("2026-08-09T00:00:00Z"))
                .expiresAt(Instant.parse("2026-08-09T00:05:00Z"))
                .build());

        LoginResponse response = authority.createSession(account, SessionAuthenticationMethod.OIDC);

        assertThat(response.accessToken()).isEqualTo("signed-token");
        verify(credentialRepository, never()).findByAccountIdForUpdate(any());
        ArgumentCaptor<AuthSession> sessionCaptor = ArgumentCaptor.forClass(AuthSession.class);
        verify(sessionRepository).saveAndFlush(sessionCaptor.capture());
        assertThat(sessionCaptor.getValue().getAuthenticationMethod())
                .isEqualTo(SessionAuthenticationMethod.OIDC);
    }

    @Test
    void passkeySessionDoesNotRequireLocalPasswordCredential() {
        UserAccount account = account();
        when(accountRepository.findByIdForUpdate(account.getId())).thenReturn(Optional.of(account));
        when(jwtEncoder.encode(any())).thenReturn(Jwt.withTokenValue("signed-token")
                .header("alg", "HS256")
                .claim("sub", account.getId().toString())
                .claim("session_id", UUID.randomUUID().toString())
                .issuedAt(Instant.parse("2026-08-09T00:00:00Z"))
                .expiresAt(Instant.parse("2026-08-09T00:05:00Z"))
                .build());

        LoginResponse response = authority.createSession(account, SessionAuthenticationMethod.PASSKEY);

        assertThat(response.accessToken()).isEqualTo("signed-token");
        verify(credentialRepository, never()).findByAccountIdForUpdate(any());
        ArgumentCaptor<AuthSession> sessionCaptor = ArgumentCaptor.forClass(AuthSession.class);
        verify(sessionRepository).saveAndFlush(sessionCaptor.capture());
        assertThat(sessionCaptor.getValue().getAuthenticationMethod())
                .isEqualTo(SessionAuthenticationMethod.PASSKEY);
    }

    private UserAccount account() {
        return accountWithId(UUID.randomUUID());
    }

    private UserAccount accountWithId(UUID id) {
        UserAccount account = new UserAccount("ada@example.com", "Ada Lovelace");
        ReflectionTestUtils.setField(account, "id", id);
        return account;
    }
}
