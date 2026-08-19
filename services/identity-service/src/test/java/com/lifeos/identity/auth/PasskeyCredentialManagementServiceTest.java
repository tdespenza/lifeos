package com.lifeos.identity.auth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifeos.identity.account.UserAccount;
import com.lifeos.identity.account.UserAccountRepository;
import com.yubico.webauthn.RelyingParty;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PasskeyCredentialManagementServiceTest {

    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final UUID CREDENTIAL_ID = UUID.randomUUID();
    private static final AuthenticatedSubject SUBJECT =
            new AuthenticatedSubject(ACCOUNT_ID, UUID.randomUUID(), "PASSWORD", "proof");

    @Mock
    private IdentityAuthProperties properties;

    @Mock
    private RelyingParty relyingParty;

    @Mock
    private UserAccountRepository accountRepository;

    @Mock
    private WebAuthnCredentialRepository credentialRepository;

    @Mock
    private PasswordCredentialRepository passwordCredentialRepository;

    @Mock
    private WebAuthnRegistrationChallengeStore challengeStore;

    @Mock
    private SecurityAuditService auditService;

    private PasskeyRegistrationService service;

    @BeforeEach
    void setUp() {
        service = new PasskeyRegistrationService(
                properties,
                relyingParty,
                accountRepository,
                credentialRepository,
                passwordCredentialRepository,
                challengeStore,
                auditService,
                new ObjectMapper());
    }

    @Test
    void refusesToRemoveTheLastUsableCredential() {
        UserAccount account = mock(UserAccount.class);
        WebAuthnCredential credential = mock(WebAuthnCredential.class);
        when(account.isActive()).thenReturn(true);
        when(accountRepository.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(credentialRepository.findByIdAndAccount_IdAndEnabledTrue(CREDENTIAL_ID, ACCOUNT_ID))
                .thenReturn(Optional.of(credential));
        when(credentialRepository.countByAccount_IdAndEnabledTrue(ACCOUNT_ID)).thenReturn(1L);
        when(passwordCredentialRepository.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.revoke(SUBJECT, CREDENTIAL_ID, "127.0.0.1"))
                .isInstanceOf(PasskeyCredentialRemovalConflictException.class);

        verify(credential, never()).disable();
        verify(credentialRepository, never()).saveAndFlush(credential);
        verify(auditService).record(
                SecurityAuditEventType.PASSKEY_CREDENTIAL_REVOCATION_REJECTED, ACCOUNT_ID, "127.0.0.1");
    }

    @Test
    void removesAUserOwnedCredentialWhenPasswordRemainsAvailable() {
        UserAccount account = mock(UserAccount.class);
        WebAuthnCredential credential = mock(WebAuthnCredential.class);
        PasswordCredential password = mock(PasswordCredential.class);
        when(account.isActive()).thenReturn(true);
        when(accountRepository.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(account));
        when(credentialRepository.findByIdAndAccount_IdAndEnabledTrue(CREDENTIAL_ID, ACCOUNT_ID))
                .thenReturn(Optional.of(credential));
        when(credentialRepository.countByAccount_IdAndEnabledTrue(ACCOUNT_ID)).thenReturn(1L);
        when(passwordCredentialRepository.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(password));
        when(password.isActive()).thenReturn(true);

        service.revoke(SUBJECT, CREDENTIAL_ID, "127.0.0.1");

        verify(credential).disable();
        verify(credentialRepository).saveAndFlush(credential);
        verify(auditService).recordWithinCurrentTransaction(
                SecurityAuditEventType.PASSKEY_CREDENTIAL_REVOKED, ACCOUNT_ID, "127.0.0.1");
    }
}
