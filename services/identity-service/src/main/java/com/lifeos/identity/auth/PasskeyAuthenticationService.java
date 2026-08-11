package com.lifeos.identity.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifeos.identity.account.UserAccount;
import com.yubico.webauthn.AssertionRequest;
import com.yubico.webauthn.AssertionResult;
import com.yubico.webauthn.RelyingParty;
import com.yubico.webauthn.exception.AssertionFailedException;
import com.yubico.webauthn.data.AuthenticatorAssertionResponse;
import com.yubico.webauthn.data.ClientAssertionExtensionOutputs;
import com.yubico.webauthn.data.PublicKeyCredential;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Orchestrates passwordless, username-less WebAuthn authentication.
 *
 * <p>Yubico's immutable {@link RelyingParty} performs protocol validation. This service owns the
 * LifeOS-specific boundaries around it: bounded Redis state, durable credential metadata, atomic
 * signature-counter persistence, shared session creation, and redacted security auditing.
 */
@Service
public class PasskeyAuthenticationService {

    private static final Logger log = LoggerFactory.getLogger(PasskeyAuthenticationService.class);
    private static final Pattern CHALLENGE_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final String PASSKEY_RATE_LIMIT_KEY = "passkey";

    private final IdentityAuthProperties properties;
    private final RelyingParty relyingParty;
    private final WebAuthnChallengeStore challengeStore;
    private final WebAuthnCredentialRepository credentialRepository;
    private final LoginRateLimiter rateLimiter;
    private final SessionTokenAuthority sessionTokenAuthority;
    private final SecurityAuditService auditService;
    private final LoginMetrics metrics;
    private final WebAuthnAssertionParser assertionParser;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final SecureRandom secureRandom;

    /**
     * Creates the passkey authentication service.
     *
     * @param properties authentication configuration
     * @param relyingParty immutable WebAuthn protocol validator
     * @param challengeStore distributed single-use assertion state
     * @param credentialRepository durable credential metadata repository
     * @param rateLimiter distributed passkey-attempt limiter
     * @param sessionTokenAuthority shared LifeOS session authority
     * @param auditService redacted security audit writer
     * @param metrics low-cardinality authentication metrics
     * @param assertionParser typed browser-assertion parser
     * @param objectMapper JSON mapper for browser response envelopes
     */
    @Autowired
    public PasskeyAuthenticationService(
            IdentityAuthProperties properties,
            RelyingParty relyingParty,
            WebAuthnChallengeStore challengeStore,
            WebAuthnCredentialRepository credentialRepository,
            LoginRateLimiter rateLimiter,
            SessionTokenAuthority sessionTokenAuthority,
            SecurityAuditService auditService,
            LoginMetrics metrics,
            WebAuthnAssertionParser assertionParser,
            ObjectMapper objectMapper) {
        this(properties, relyingParty, challengeStore, credentialRepository, rateLimiter,
                sessionTokenAuthority, auditService, metrics, assertionParser, objectMapper,
                Clock.systemUTC(), new SecureRandom());
    }

    PasskeyAuthenticationService(
            IdentityAuthProperties properties,
            RelyingParty relyingParty,
            WebAuthnChallengeStore challengeStore,
            WebAuthnCredentialRepository credentialRepository,
            LoginRateLimiter rateLimiter,
            SessionTokenAuthority sessionTokenAuthority,
            SecurityAuditService auditService,
            LoginMetrics metrics,
            WebAuthnAssertionParser assertionParser,
            ObjectMapper objectMapper,
            Clock clock,
            SecureRandom secureRandom) {
        this.properties = properties;
        this.relyingParty = relyingParty;
        this.challengeStore = challengeStore;
        this.credentialRepository = credentialRepository;
        this.rateLimiter = rateLimiter;
        this.sessionTokenAuthority = sessionTokenAuthority;
        this.auditService = auditService;
        this.metrics = metrics;
        this.assertionParser = assertionParser;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.secureRandom = secureRandom;
    }

    /**
     * Starts a passwordless passkey ceremony and persists the exact assertion request server-side.
     *
     * @param clientAddress request source used only for keyed audit fingerprinting
     * @return browser options plus the opaque single-use challenge handle
     */
    public PasskeyAuthenticationOptions begin(String clientAddress) {
        try {
            rateLimiter.check(PASSKEY_RATE_LIMIT_KEY, clientAddress);
            AssertionRequest request = relyingParty.startAssertion(
                    com.yubico.webauthn.StartAssertionOptions.builder()
                            .userVerification(properties.getWebauthn().getUserVerification())
                            .build());
            JsonNode optionsEnvelope = objectMapper.readTree(request.toCredentialsGetJson());
            JsonNode publicKeyOptions = optionsEnvelope == null ? null : optionsEnvelope.get("publicKey");
            if (publicKeyOptions == null || !publicKeyOptions.isObject()) {
                throw new AuthenticationDependencyUnavailableException();
            }
            String challengeId = randomChallengeId();
            challengeStore.save(challengeId, request, properties.getWebauthn().getChallengeTtl());
            return new PasskeyAuthenticationOptions(challengeId, publicKeyOptions);
        } catch (AuthenticationDependencyUnavailableException exception) {
            recordAudit(SecurityAuditEventType.PASSKEY_DEPENDENCY_UNAVAILABLE, null, clientAddress);
            throw exception;
        } catch (LoginRateLimitExceededException exception) {
            recordAudit(SecurityAuditEventType.PASSKEY_LOGIN_RATE_LIMITED, null, clientAddress);
            throw exception;
        } catch (JsonProcessingException | RuntimeException exception) {
            recordAudit(SecurityAuditEventType.PASSKEY_DEPENDENCY_UNAVAILABLE, null, clientAddress);
            throw new AuthenticationDependencyUnavailableException(exception);
        }
    }

    /**
     * Consumes and verifies one browser assertion, then creates a shared PASSKEY session.
     *
     * @param request challenge handle and browser assertion
     * @param clientAddress request source used only for keyed audit fingerprinting
     * @return shared session/token response
     */
    @Transactional
    public LoginResponse complete(PasskeyAuthenticationRequest request, String clientAddress) {
        try {
            rateLimiter.check(PASSKEY_RATE_LIMIT_KEY, clientAddress);
            validateRequest(request);
            AssertionRequest assertionRequest = challengeStore.consume(request.challengeId())
                    .orElseThrow(AuthenticationFailureException::new);
            PublicKeyCredential<AuthenticatorAssertionResponse, ClientAssertionExtensionOutputs> assertion =
                    assertionParser.parse(objectMapper.writeValueAsString(request.credential()));
            AssertionResult result = relyingParty.finishAssertion(
                    com.yubico.webauthn.FinishAssertionOptions.builder()
                            .request(assertionRequest)
                            .response(assertion)
                            .build());
            if (!result.isSuccess() || !result.isUserVerified()) {
                throw new AuthenticationFailureException();
            }

            String credentialId = result.getCredential().getCredentialId().getBase64Url();
            WebAuthnCredential credential = credentialRepository
                    .findByCredentialIdAndEnabledTrue(credentialId)
                    .orElseThrow(AuthenticationFailureException::new);
            long nextSignatureCount = result.getSignatureCount();
            if (nextSignatureCount < 0
                    || credentialRepository.advanceSignatureCountIfCurrent(
                            credential.getId(),
                            credential.getSignatureCount(),
                            nextSignatureCount,
                            clock.instant()) != 1) {
                throw new AuthenticationFailureException();
            }

            UserAccount account = credential.getAccount();
            LoginResponse response = sessionTokenAuthority.createSession(
                    account, SessionAuthenticationMethod.PASSKEY);
            recordSuccessfulAudit(account, clientAddress);
            return response;
        } catch (SessionCapacityExceededException exception) {
            recordAudit(SecurityAuditEventType.PASSKEY_SESSION_CAPACITY_REACHED, null, clientAddress);
            throw exception;
        } catch (AuthenticationFailureException exception) {
            recordAudit(SecurityAuditEventType.PASSKEY_ASSERTION_REJECTED, null, clientAddress);
            throw exception;
        } catch (AuthenticationDependencyUnavailableException exception) {
            recordAudit(SecurityAuditEventType.PASSKEY_DEPENDENCY_UNAVAILABLE, null, clientAddress);
            throw exception;
        } catch (LoginRateLimitExceededException exception) {
            recordAudit(SecurityAuditEventType.PASSKEY_LOGIN_RATE_LIMITED, null, clientAddress);
            throw exception;
        } catch (AssertionFailedException | IOException exception) {
            recordAudit(SecurityAuditEventType.PASSKEY_ASSERTION_REJECTED, null, clientAddress);
            throw new AuthenticationFailureException();
        } catch (DataAccessException exception) {
            recordAudit(SecurityAuditEventType.PASSKEY_DEPENDENCY_UNAVAILABLE, null, clientAddress);
            throw new AuthenticationDependencyUnavailableException(exception);
        } catch (RuntimeException exception) {
            recordAudit(SecurityAuditEventType.PASSKEY_ASSERTION_REJECTED, null, clientAddress);
            throw new AuthenticationFailureException();
        }
    }

    private void validateRequest(PasskeyAuthenticationRequest request) {
        if (request == null || request.challengeId() == null || request.credential() == null
                || !CHALLENGE_ID_PATTERN.matcher(request.challengeId()).matches()
                || !request.credential().isObject()
                || request.credential().size() == 0) {
            throw new AuthenticationFailureException();
        }
    }

    private String randomChallengeId() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void recordSuccessfulAudit(UserAccount account, String clientAddress) {
        try {
            auditService.recordWithinCurrentTransaction(
                    SecurityAuditEventType.PASSKEY_LOGIN_SUCCEEDED, account.getId(), clientAddress);
            Runnable committedOutcome = () -> metrics.record(SecurityAuditEventType.PASSKEY_LOGIN_SUCCEEDED);
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        committedOutcome.run();
                    }
                });
            } else {
                committedOutcome.run();
            }
        } catch (RuntimeException exception) {
            log.atError().addKeyValue("event", "passkey_login_audit_failed")
                    .log("Passkey authentication audit persistence failed");
            throw new AuthenticationDependencyUnavailableException(exception);
        }
    }

    private void recordAudit(SecurityAuditEventType eventType, UUID accountId, String clientAddress) {
        try {
            auditService.record(eventType, accountId, clientAddress);
            metrics.record(eventType);
        } catch (RuntimeException exception) {
            log.atError().addKeyValue("event", "passkey_audit_failed")
                    .log("Passkey authentication audit persistence failed");
            throw new AuthenticationDependencyUnavailableException(exception);
        }
    }
}
