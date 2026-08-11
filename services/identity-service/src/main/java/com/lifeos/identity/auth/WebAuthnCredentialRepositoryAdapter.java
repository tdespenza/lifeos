package com.lifeos.identity.auth;

import com.lifeos.identity.account.UserAccountRepository;
import com.yubico.webauthn.CredentialRepository;
import com.yubico.webauthn.RegisteredCredential;
import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.PublicKeyCredentialDescriptor;
import com.yubico.webauthn.data.PublicKeyCredentialType;
import com.yubico.webauthn.data.exception.Base64UrlException;
import com.lifeos.identity.account.UserAccount;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Adapts the identity-service WebAuthn credential store to Yubico's relying-party API.
 *
 * <p>The adapter returns only enabled credentials and reconstructs short-lived
 * {@link RegisteredCredential} values from durable public-key metadata. It never exposes private
 * authenticator material because none is present in the persistence model.
 */
@Component
public class WebAuthnCredentialRepositoryAdapter implements CredentialRepository {

    private static final Logger log = LoggerFactory.getLogger(WebAuthnCredentialRepositoryAdapter.class);

    private final WebAuthnCredentialRepository credentialRepository;
    private final UserAccountRepository accountRepository;

    /**
     * Creates the WebAuthn credential repository adapter.
     *
     * @param credentialRepository durable credential metadata repository
     * @param accountRepository account repository
     */
    public WebAuthnCredentialRepositoryAdapter(
            WebAuthnCredentialRepository credentialRepository,
            UserAccountRepository accountRepository) {
        this.credentialRepository = credentialRepository;
        this.accountRepository = accountRepository;
    }

    @Override
    public Set<PublicKeyCredentialDescriptor> getCredentialIdsForUsername(String username) {
        Optional<UserAccount> account = accountRepository.findByEmail(username);
        if (account.isEmpty()) {
            return Collections.emptySet();
        }
        Set<PublicKeyCredentialDescriptor> descriptors = new LinkedHashSet<>();
        credentialRepository.findAllByAccount_IdAndEnabledTrue(account.get().getId())
                .forEach(credential -> descriptor(credential).ifPresent(descriptors::add));
        return descriptors;
    }

    @Override
    public Optional<ByteArray> getUserHandleForUsername(String username) {
        return accountRepository.findByEmail(username)
                .flatMap(account -> credentialRepository.findAllByAccount_IdAndEnabledTrue(account.getId())
                        .stream()
                        .findFirst()
                        .flatMap(this::userHandle));
    }

    @Override
    public Optional<String> getUsernameForUserHandle(ByteArray userHandle) {
        if (userHandle == null || userHandle.isEmpty()) {
            return Optional.empty();
        }
        return credentialRepository.findByUserHandleAndEnabledTrue(userHandle.getBase64Url())
                .stream()
                .map(credential -> credential.getAccount().getEmail())
                .findFirst();
    }

    @Override
    public Optional<RegisteredCredential> lookup(ByteArray credentialId, ByteArray userHandle) {
        if (credentialId == null || credentialId.isEmpty()) {
            return Optional.empty();
        }
        return credentialRepository.findByCredentialIdAndEnabledTrue(credentialId.getBase64Url())
                .filter(credential -> userHandle == null
                        || userHandle.equals(toByteArray(credential, credential.getUserHandle()).orElse(null)))
                .flatMap(this::registeredCredential);
    }

    @Override
    public Set<RegisteredCredential> lookupAll(ByteArray credentialId) {
        if (credentialId == null || credentialId.isEmpty()) {
            return Collections.emptySet();
        }
        return credentialRepository.findByCredentialIdAndEnabledTrue(credentialId.getBase64Url())
                .flatMap(this::registeredCredential)
                .map(Set::of)
                .orElseGet(Collections::emptySet);
    }

    private Optional<PublicKeyCredentialDescriptor> descriptor(WebAuthnCredential credential) {
        return toByteArray(credential, credential.getCredentialId())
                .map(id -> PublicKeyCredentialDescriptor.builder()
                        .id(id)
                        .type(PublicKeyCredentialType.PUBLIC_KEY)
                        .build());
    }

    private Optional<ByteArray> userHandle(WebAuthnCredential credential) {
        return toByteArray(credential, credential.getUserHandle());
    }

    private Optional<RegisteredCredential> registeredCredential(WebAuthnCredential credential) {
        try {
            return Optional.of(RegisteredCredential.builder()
                    .credentialId(ByteArray.fromBase64Url(credential.getCredentialId()))
                    .userHandle(ByteArray.fromBase64Url(credential.getUserHandle()))
                    .publicKeyCose(ByteArray.fromBase64(credential.getPublicKeyCose()))
                    .signatureCount(credential.getSignatureCount())
                    .build());
        } catch (Base64UrlException | IllegalArgumentException exception) {
            logDecodeFailure(credential, exception);
            return Optional.empty();
        }
    }

    private Optional<ByteArray> toByteArray(WebAuthnCredential credential, String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(ByteArray.fromBase64Url(value));
        } catch (Base64UrlException | IllegalArgumentException exception) {
            logDecodeFailure(credential, exception);
            return Optional.empty();
        }
    }

    private void logDecodeFailure(WebAuthnCredential credential, Exception exception) {
        log.atWarn()
                .addKeyValue("event", "webauthn_credential_decode_failed")
                .addKeyValue("credentialRowId", credential.getId())
                .setCause(exception)
                .log("Stored WebAuthn credential metadata could not be decoded");
    }
}
