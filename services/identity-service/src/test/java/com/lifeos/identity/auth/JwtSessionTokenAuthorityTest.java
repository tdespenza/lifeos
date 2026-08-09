package com.lifeos.identity.auth;

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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
        PasswordCredential credential = new PasswordCredential(lockedAccount, "argon2-hash");
        when(accountRepository.findByIdForUpdate(requestedAccount.getId()))
                .thenReturn(Optional.of(lockedAccount));
        when(credentialRepository.findByAccountIdForUpdate(requestedAccount.getId()))
                .thenReturn(Optional.of(credential));

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

    private UserAccount account() {
        return accountWithId(UUID.randomUUID());
    }

    private UserAccount accountWithId(UUID id) {
        UserAccount account = new UserAccount("ada@example.com", "Ada Lovelace");
        ReflectionTestUtils.setField(account, "id", id);
        return account;
    }
}
