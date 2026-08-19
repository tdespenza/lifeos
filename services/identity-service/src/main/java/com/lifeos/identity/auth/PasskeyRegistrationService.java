package com.lifeos.identity.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifeos.identity.account.UserAccount;
import com.lifeos.identity.account.UserAccountRepository;
import com.yubico.webauthn.FinishRegistrationOptions;
import com.yubico.webauthn.RegistrationResult;
import com.yubico.webauthn.RelyingParty;
import com.yubico.webauthn.StartRegistrationOptions;
import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.ClientRegistrationExtensionOutputs;
import com.yubico.webauthn.data.AuthenticatorAttestationResponse;
import com.yubico.webauthn.data.PublicKeyCredential;
import com.yubico.webauthn.data.PublicKeyCredentialCreationOptions;
import com.yubico.webauthn.data.UserIdentity;
import com.yubico.webauthn.exception.RegistrationFailedException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Authenticated, single-use WebAuthn credential enrollment. */
@Service
public class PasskeyRegistrationService {

    private final IdentityAuthProperties properties;
    private final RelyingParty relyingParty;
    private final UserAccountRepository accountRepository;
    private final WebAuthnCredentialRepository credentialRepository;
    private final PasswordCredentialRepository passwordCredentialRepository;
    private final WebAuthnRegistrationChallengeStore challengeStore;
    private final SecurityAuditService auditService;
    private final ObjectMapper objectMapper;
    private final SecureRandom secureRandom = new SecureRandom();

    public PasskeyRegistrationService(
            IdentityAuthProperties properties,
            RelyingParty relyingParty,
            UserAccountRepository accountRepository,
            WebAuthnCredentialRepository credentialRepository,
            PasswordCredentialRepository passwordCredentialRepository,
            WebAuthnRegistrationChallengeStore challengeStore,
            SecurityAuditService auditService,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.relyingParty = relyingParty;
        this.accountRepository = accountRepository;
        this.credentialRepository = credentialRepository;
        this.passwordCredentialRepository = passwordCredentialRepository;
        this.challengeStore = challengeStore;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<PasskeyCredentialSummary> list(AuthenticatedSubject subject) {
        if (subject == null) {
            throw new AuthenticationFailureException();
        }
        return credentialRepository.findAllByAccount_IdAndEnabledTrueOrderByCreatedAtAscIdAsc(subject.accountId()).stream()
                .map(credential -> new PasskeyCredentialSummary(
                        credential.getId(), credential.getCreatedAt(), credential.getLastUsedAt()))
                .toList();
    }

    @Transactional(timeout = 5)
    public void revoke(AuthenticatedSubject subject, UUID credentialId, String clientAddress) {
        if (subject == null || credentialId == null) {
            throw new PasskeyCredentialNotFoundException();
        }
        accountRepository.findByIdForUpdate(subject.accountId())
                .filter(UserAccount::isActive)
                .orElseThrow(PasskeyCredentialNotFoundException::new);
        WebAuthnCredential credential = credentialRepository
                .findByIdAndAccount_IdAndEnabledTrue(credentialId, subject.accountId())
                .orElseThrow(PasskeyCredentialNotFoundException::new);
        boolean alternatePasskey = credentialRepository.countByAccount_IdAndEnabledTrue(subject.accountId()) > 1;
        boolean alternatePassword = passwordCredentialRepository.findByAccountId(subject.accountId())
                .filter(PasswordCredential::isActive)
                .isPresent();
        if (!alternatePasskey && !alternatePassword) {
            auditService.record(
                    SecurityAuditEventType.PASSKEY_CREDENTIAL_REVOCATION_REJECTED,
                    subject.accountId(),
                    clientAddress);
            throw new PasskeyCredentialRemovalConflictException();
        }
        credential.disable();
        credentialRepository.saveAndFlush(credential);
        auditService.recordWithinCurrentTransaction(
                SecurityAuditEventType.PASSKEY_CREDENTIAL_REVOKED, subject.accountId(), clientAddress);
    }

    public PasskeyRegistrationOptions begin(AuthenticatedSubject subject, String clientAddress) {
        if (subject == null) {
            throw new AuthenticationFailureException();
        }
        try {
            UserAccount account = accountRepository.findById(subject.accountId())
                    .filter(UserAccount::isActive)
                    .orElseThrow(AuthenticationFailureException::new);
            ByteArray userHandle = userHandle(account.getId());
            UserIdentity user = UserIdentity.builder()
                    .name(account.getEmail())
                    .displayName(account.getDisplayName())
                    .id(userHandle)
                    .build();
            PublicKeyCredentialCreationOptions request = relyingParty.startRegistration(
                    StartRegistrationOptions.builder().user(user).build());
            JsonNode envelope = objectMapper.readTree(request.toCredentialsCreateJson());
            JsonNode publicKey = envelope == null ? null : envelope.get("publicKey");
            if (publicKey == null || !publicKey.isObject()) {
                throw new AuthenticationDependencyUnavailableException();
            }
            WebAuthnChallengeId id = WebAuthnChallengeId.generate(secureRandom);
            challengeStore.save(
                    id,
                    new WebAuthnRegistrationChallenge(account.getId(), request),
                    properties.getWebauthn().getChallengeTtl());
            auditService.record(SecurityAuditEventType.PASSKEY_REGISTRATION_STARTED, account.getId(), clientAddress);
            return new PasskeyRegistrationOptions(id.value(), publicKey);
        } catch (AuthenticationFailureException | AuthenticationDependencyUnavailableException exception) {
            throw exception;
        } catch (JsonProcessingException | RuntimeException exception) {
            throw new AuthenticationDependencyUnavailableException(exception);
        }
    }

    @Transactional
    public void complete(
            AuthenticatedSubject subject,
            PasskeyRegistrationRequest request,
            String clientAddress) {
        if (subject == null || request == null || request.challengeId() == null || request.credential() == null) {
            throw new AuthenticationFailureException();
        }
        WebAuthnChallengeId challengeId = WebAuthnChallengeId.parse(request.challengeId())
                .orElseThrow(AuthenticationFailureException::new);
        WebAuthnRegistrationChallenge challenge = challengeStore.consume(challengeId)
                .orElseThrow(AuthenticationFailureException::new);
        if (!subject.accountId().equals(challenge.accountId())) {
            throw new AuthenticationFailureException();
        }
        try {
            UserAccount account = accountRepository.findById(subject.accountId())
                    .filter(UserAccount::isActive)
                    .orElseThrow(AuthenticationFailureException::new);
            PublicKeyCredential<AuthenticatorAttestationResponse, ClientRegistrationExtensionOutputs> credential =
                    PublicKeyCredential.parseRegistrationResponseJson(objectMapper.writeValueAsString(request.credential()));
            RegistrationResult result = relyingParty.finishRegistration(
                    FinishRegistrationOptions.builder()
                            .request(challenge.request())
                            .response(credential)
                            .build());
            if (!result.isUserVerified() && properties.getWebauthn().getUserVerification()
                    == com.yubico.webauthn.data.UserVerificationRequirement.REQUIRED) {
                throw new AuthenticationFailureException();
            }
            credentialRepository.saveAndFlush(new WebAuthnCredential(
                    account,
                    result.getKeyId().getId(),
                    userHandle(account.getId()),
                    result.getPublicKeyCose(),
                    result.getSignatureCount()));
            auditService.recordWithinCurrentTransaction(
                    SecurityAuditEventType.PASSKEY_REGISTRATION_SUCCEEDED, account.getId(), clientAddress);
        } catch (AuthenticationFailureException exception) {
            auditService.record(SecurityAuditEventType.PASSKEY_REGISTRATION_REJECTED, subject.accountId(), clientAddress);
            throw exception;
        } catch (RegistrationFailedException | IOException | DataAccessException exception) {
            auditService.record(SecurityAuditEventType.PASSKEY_REGISTRATION_REJECTED, subject.accountId(), clientAddress);
            throw new AuthenticationFailureException(exception);
        } catch (RuntimeException exception) {
            auditService.record(SecurityAuditEventType.PASSKEY_REGISTRATION_REJECTED, subject.accountId(), clientAddress);
            throw new AuthenticationDependencyUnavailableException(exception);
        }
    }

    private static ByteArray userHandle(UUID accountId) {
        byte[] bytes = accountId.toString().getBytes(StandardCharsets.UTF_8);
        return new ByteArray(bytes);
    }
}
