package com.lifeos.identity.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.lifeos.identity.account.UserAccount;
import com.lifeos.identity.account.UserAccountRepository;
import com.yubico.webauthn.RegisteredCredential;
import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.PublicKeyCredentialDescriptor;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Verifies enabled credential lookup and public-key-only reconstruction for the WebAuthn library.
 */
@ExtendWith(MockitoExtension.class)
class WebAuthnCredentialRepositoryAdapterTest {

    @Mock
    private WebAuthnCredentialRepository credentialRepository;
    @Mock
    private UserAccountRepository accountRepository;

    private WebAuthnCredentialRepositoryAdapter adapter;
    private UserAccount account;
    private WebAuthnCredential credential;

    @BeforeEach
    void setUp() throws Exception {
        adapter = new WebAuthnCredentialRepositoryAdapter(credentialRepository, accountRepository);
        account = new UserAccount("ada@example.com", "Ada Lovelace");
        ReflectionTestUtils.setField(account, "id", UUID.randomUUID());
        credential = new WebAuthnCredential(
                account,
                ByteArray.fromBase64Url("Y3JlZGVudGlhbC1pZA"),
                ByteArray.fromBase64Url("dXNlci1oYW5kbGU"),
                new ByteArray(new byte[] {1, 2, 3}),
                7);
        ReflectionTestUtils.setField(credential, "id", UUID.randomUUID());
    }

    @Test
    void lookupReconstructsCredentialWithStoredPublicKeyAndCounter() throws Exception {
        when(credentialRepository.findByCredentialIdAndEnabledTrue(credential.getCredentialId()))
                .thenReturn(Optional.of(credential));

        Optional<RegisteredCredential> result = adapter.lookup(
                ByteArray.fromBase64Url(credential.getCredentialId()),
                ByteArray.fromBase64Url(credential.getUserHandle()));

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().getCredentialId().getBase64Url())
                .isEqualTo(credential.getCredentialId());
        assertThat(result.orElseThrow().getUserHandle().getBase64Url())
                .isEqualTo(credential.getUserHandle());
        assertThat(result.orElseThrow().getPublicKeyCose().getBase64())
                .isEqualTo(credential.getPublicKeyCose());
        assertThat(result.orElseThrow().getSignatureCount()).isEqualTo(7);
    }

    @Test
    void usernameLookupMapsCredentialDescriptorsForAccount() throws Exception {
        when(accountRepository.findByEmail(account.getEmail())).thenReturn(Optional.of(account));
        when(credentialRepository.findAllByAccount_IdAndEnabledTrue(account.getId()))
                .thenReturn(List.of(credential));

        java.util.Set<PublicKeyCredentialDescriptor> descriptors =
                adapter.getCredentialIdsForUsername(account.getEmail());

        assertThat(descriptors).singleElement()
                .extracting(descriptor -> descriptor.getId().getBase64Url())
                .isEqualTo(credential.getCredentialId());
    }

    @Test
    void userHandleLookupUsesTheFirstCredentialForTheAccount() throws Exception {
        when(credentialRepository.findByUserHandleAndEnabledTrue(credential.getUserHandle()))
                .thenReturn(List.of(credential));

        assertThat(adapter.getUsernameForUserHandle(
                ByteArray.fromBase64Url(credential.getUserHandle())))
                .contains(account.getEmail());
    }

    @Test
    void mismatchedUserHandleCannotUseCredential() throws Exception {
        when(credentialRepository.findByCredentialIdAndEnabledTrue(credential.getCredentialId()))
                .thenReturn(Optional.of(credential));

        assertThat(adapter.lookup(
                ByteArray.fromBase64Url(credential.getCredentialId()),
                ByteArray.fromBase64Url("b3RoZXItaGFuZGxl"))).isEmpty();
    }
}
