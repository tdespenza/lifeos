package com.lifeos.identity.auth;

import com.lifeos.identity.account.EmailAddressNormalizer;
import com.lifeos.identity.account.UserAccount;
import com.lifeos.identity.account.UserAccountRepository;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * OIDC authorization-code orchestration and account-linking policy.
 */
@Service
public class OidcAuthenticationService {

    private static final Logger log = LoggerFactory.getLogger(OidcAuthenticationService.class);
    private static final Pattern CODE_VERIFIER_PATTERN = Pattern.compile("[A-Za-z0-9._~-]{43,128}");
    private static final Pattern CODE_CHALLENGE_PATTERN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final Pattern BROWSER_TRANSACTION_PATTERN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+$");
    private static final int MAX_EMAIL_LENGTH = 320;
    private static final int MAX_DISPLAY_NAME_LENGTH = 200;

    private final IdentityAuthProperties properties;
    private final OidcStateStore stateStore;
    private final OidcProviderClient providerClient;
    private final ExternalIdentityRepository externalIdentityRepository;
    private final UserAccountRepository accountRepository;
    private final SessionTokenAuthority sessionTokenAuthority;
    private final SecurityAuditService auditService;
    private final LoginMetrics metrics;
    private final SecureRandom secureRandom;

    /**
     * Creates the OIDC authentication service.
     *
     * @param properties authentication configuration
     * @param stateStore distributed callback-state store
     * @param providerClient provider exchange and ID-token validator
     * @param externalIdentityRepository provider-subject mappings
     * @param accountRepository account identities
     * @param sessionTokenAuthority shared LifeOS session authority
     * @param auditService redacted audit writer
     * @param metrics authentication metrics
     */
    @Autowired
    public OidcAuthenticationService(
            IdentityAuthProperties properties,
            OidcStateStore stateStore,
            OidcProviderClient providerClient,
            ExternalIdentityRepository externalIdentityRepository,
            UserAccountRepository accountRepository,
            SessionTokenAuthority sessionTokenAuthority,
            SecurityAuditService auditService,
            LoginMetrics metrics) {
        this(properties, stateStore, providerClient, externalIdentityRepository, accountRepository,
                sessionTokenAuthority, auditService, metrics, new SecureRandom());
    }

    OidcAuthenticationService(
            IdentityAuthProperties properties,
            OidcStateStore stateStore,
            OidcProviderClient providerClient,
            ExternalIdentityRepository externalIdentityRepository,
            UserAccountRepository accountRepository,
            SessionTokenAuthority sessionTokenAuthority,
            SecurityAuditService auditService,
            LoginMetrics metrics,
            SecureRandom secureRandom) {
        this.properties = properties;
        this.stateStore = stateStore;
        this.providerClient = providerClient;
        this.externalIdentityRepository = externalIdentityRepository;
        this.accountRepository = accountRepository;
        this.sessionTokenAuthority = sessionTokenAuthority;
        this.auditService = auditService;
        this.metrics = metrics;
        this.secureRandom = secureRandom;
    }

    /**
     * Browser authorization redirect plus the transaction material that must be delivered only in
     * an HttpOnly, Secure, SameSite cookie.
     *
     * @param authorizationUri configured provider authorization URI
     * @param state single-use callback state
     * @param browserTransaction opaque browser-bound transaction secret
     * @param ttl transaction cookie lifetime
     */
    public record BrowserAuthorizationStart(
            URI authorizationUri,
            String state,
            String browserTransaction,
            Duration ttl) {

        @Override
        public String toString() {
            return "BrowserAuthorizationStart[authorizationUri=" + authorizationUri
                    + ", state=<redacted>, browserTransaction=<redacted>, ttl=" + ttl + ']';
        }
    }

    /**
     * Creates a single-use callback state and returns the configured provider authorization URI.
     *
     * @param providerName allow-listed provider name
     * @param request client PKCE challenge
     * @return provider authorization URI
     */
    public URI begin(String providerName, OidcAuthorizationRequest request) {
        return beginInternal(providerName, request, null, null).authorizationUri();
    }

    /**
     * Creates browser-bound callback state with a server-held verifier for a browser redirect
     * flow.
     *
     * @param providerName allow-listed provider name
     * @param request client PKCE challenge
     * @param codeVerifier client-generated PKCE verifier retained for the callback
     * @return provider authorization URI and browser transaction material
     */
    public BrowserAuthorizationStart beginBrowser(
            String providerName, OidcAuthorizationRequest request, String codeVerifier) {
        String browserTransaction = randomValue();
        AuthorizationStart authorizationStart = beginInternal(
                providerName,
                request,
                codeVerifier,
                browserTransactionHash(browserTransaction));
        return new BrowserAuthorizationStart(
                authorizationStart.authorizationUri(),
                authorizationStart.state(),
                browserTransaction,
                properties.getOidc().getCallbackStateTtl());
    }

    private AuthorizationStart beginInternal(
            String providerName,
            OidcAuthorizationRequest request,
            String codeVerifier,
            String browserTransactionHash) {
        IdentityAuthProperties.Provider provider = provider(providerName);
        validateProviderTransport(provider);
        validatePkceChallenge(request);
        if (codeVerifier != null
                && (!CODE_VERIFIER_PATTERN.matcher(codeVerifier).matches()
                || browserTransactionHash == null
                || !verifyPkce(request.codeChallenge(), codeVerifier))) {
            throw new OidcAuthenticationFailureException();
        }
        String state = randomValue();
        String nonce = randomValue();
        OidcAuthorizationState authorizationState = codeVerifier == null
                ? new OidcAuthorizationState(
                        providerName,
                        provider.getRedirectUri(),
                        request.codeChallenge(),
                        request.codeChallengeMethod(),
                        nonce)
                : OidcAuthorizationState.forBrowserRedirect(
                        providerName,
                        provider.getRedirectUri(),
                        request.codeChallenge(),
                        request.codeChallengeMethod(),
                        nonce,
                        codeVerifier,
                        browserTransactionHash);
        stateStore.save(state, authorizationState, properties.getOidc().getCallbackStateTtl());
        URI authorizationUri = UriComponentsBuilder.fromUriString(provider.getAuthorizationUri())
                .queryParam("response_type", "code")
                .queryParam("client_id", provider.getClientId())
                .queryParam("redirect_uri", provider.getRedirectUri())
                .queryParam("scope", provider.getScope())
                .queryParam("state", state)
                .queryParam("nonce", authorizationState.nonce())
                .queryParam("code_challenge", request.codeChallenge())
                .queryParam("code_challenge_method", request.codeChallengeMethod())
                .build()
                .encode()
                .toUri();
        return new AuthorizationStart(authorizationUri, state);
    }

    /**
     * Consumes and validates one provider callback, then creates a shared LifeOS session.
     *
     * <p>A new verified provider email creates a new LifeOS account. A provider assertion whose
     * email already belongs to an unlinked account is rejected: matching email alone is never an
     * account takeover or implicit link. Explicit linking and step-up authentication are later
     * account-management stories.
     *
     * @param providerName allow-listed provider name
     * @param code provider authorization code
     * @param state callback state
     * @param codeVerifier client PKCE verifier, unless a verifier was retained at authorization
     *     start
     * @param providerError provider-reported error, if any
     * @param clientAddress request source used only for keyed audit fingerprinting
     * @return shared session/token result
     */
    @Transactional
    public LoginResponse callback(
            String providerName,
            String code,
            String state,
            String codeVerifier,
            String providerError,
            String clientAddress) {
        return callback(providerName, code, state, codeVerifier, providerError, null, clientAddress);
    }

    /**
     * Consumes and validates one provider callback with browser transaction binding, then creates
     * a shared LifeOS session.
     *
     * @param providerName allow-listed provider name
     * @param code provider authorization code
     * @param state callback state
     * @param codeVerifier client PKCE verifier, unless a verifier was retained at authorization
     *     start
     * @param providerError provider-reported error, if any
     * @param browserTransaction transaction cookie value for browser authorization state
     * @param clientAddress request source used only for keyed audit fingerprinting
     * @return shared session/token result
     */
    @Transactional
    public LoginResponse callback(
            String providerName,
            String code,
            String state,
            String codeVerifier,
            String providerError,
            String browserTransaction,
            String clientAddress) {
        return callback(
                providerName,
                code,
                state,
                codeVerifier,
                providerError,
                browserTransaction,
                clientAddress,
                DeviceMetadata.unknown());
    }

    /**
     * Consumes a provider callback and stores only coarse device metadata with the new session.
     *
     * @param providerName allow-listed provider name
     * @param code provider authorization code
     * @param state callback state
     * @param codeVerifier client PKCE verifier
     * @param providerError provider error, if any
     * @param browserTransaction browser transaction binding
     * @param clientAddress source used only for keyed audit fingerprinting
     * @param deviceMetadata safe device classification
     * @return shared session/token result
     */
    @Transactional
    public LoginResponse callback(
            String providerName,
            String code,
            String state,
            String codeVerifier,
            String providerError,
            String browserTransaction,
            String clientAddress,
            DeviceMetadata deviceMetadata) {
        try {
            IdentityAuthProperties.Provider provider = provider(providerName);
            validateProviderTransport(provider);
            if (hasText(providerError) || !hasText(code) || !hasText(state)) {
                throw new OidcAuthenticationFailureException();
            }
            String transactionHash = browserTransactionHash(browserTransaction);
            OidcAuthorizationState authorizationState = stateStore.consume(state, transactionHash)
                    .orElseThrow(OidcAuthenticationFailureException::new);
            String effectiveCodeVerifier = hasText(authorizationState.codeVerifier())
                    ? authorizationState.codeVerifier() : codeVerifier;
            if (!browserTransactionMatches(authorizationState.browserTransactionHash(), transactionHash)
                    || !providerName.equals(authorizationState.provider())
                    || !provider.getRedirectUri().equals(authorizationState.redirectUri())
                    || !hasText(effectiveCodeVerifier)
                    || !CODE_VERIFIER_PATTERN.matcher(effectiveCodeVerifier).matches()
                    || !verifyPkce(authorizationState.codeChallenge(), effectiveCodeVerifier)) {
                throw new OidcAuthenticationFailureException();
            }

            OidcIdentity identity = providerClient.exchangeAndValidate(
                    provider, code, effectiveCodeVerifier, authorizationState.nonce());
            UserAccount account = resolveAccount(providerName, identity);
            LoginResponse response = deviceMetadata == null || deviceMetadata.isUnknown()
                    ? sessionTokenAuthority.createSession(account, SessionAuthenticationMethod.OIDC)
                    : sessionTokenAuthority.createSession(
                            account, SessionAuthenticationMethod.OIDC, deviceMetadata);
            recordSuccessfulAudit(account, clientAddress);
            return response;
        } catch (SessionCapacityExceededException exception) {
            recordAudit(SecurityAuditEventType.OIDC_SESSION_CAPACITY_REACHED, null, clientAddress);
            throw exception;
        } catch (OidcAuthenticationFailureException exception) {
            recordAudit(SecurityAuditEventType.OIDC_CALLBACK_REJECTED, null, clientAddress);
            throw exception;
        } catch (AuthenticationFailureException exception) {
            recordAudit(SecurityAuditEventType.OIDC_CALLBACK_REJECTED, null, clientAddress);
            throw exception;
        } catch (AuthenticationDependencyUnavailableException exception) {
            recordAudit(SecurityAuditEventType.OIDC_DEPENDENCY_UNAVAILABLE, null, clientAddress);
            throw exception;
        } catch (DataAccessException exception) {
            recordAudit(SecurityAuditEventType.OIDC_DEPENDENCY_UNAVAILABLE, null, clientAddress);
            throw new AuthenticationDependencyUnavailableException(exception);
        } catch (RuntimeException exception) {
            recordAudit(SecurityAuditEventType.OIDC_CALLBACK_REJECTED, null, clientAddress);
            throw new OidcAuthenticationFailureException(exception);
        }
    }

    private UserAccount resolveAccount(String providerName, OidcIdentity identity) {
        if (!hasText(identity.subject()) || identity.subject().length() > 255) {
            throw new OidcAuthenticationFailureException();
        }
        String email = validateEmail(identity.email());
        ExternalIdentity linkedIdentity = externalIdentityRepository
                .findByProviderAndSubject(providerName, identity.subject())
                .orElse(null);
        if (linkedIdentity != null) {
            UserAccount account = accountRepository.findById(linkedIdentity.getAccountId())
                    .orElseThrow(OidcAuthenticationFailureException::new);
            if (!account.isActive()) {
                throw new OidcAuthenticationFailureException();
            }
            return account;
        }

        if (accountRepository.findByEmail(email).isPresent()) {
            throw new OidcAuthenticationFailureException();
        }

        UserAccount account;
        try {
            account = accountRepository.saveAndFlush(
                    new UserAccount(email, boundedDisplayName(identity.displayName(), email)));
        } catch (DataIntegrityViolationException exception) {
            // A concurrent callback may have created the same email account. Never link implicitly.
            throw new OidcAuthenticationFailureException(exception);
        }
        try {
            externalIdentityRepository.saveAndFlush(
                    new ExternalIdentity(providerName, identity.subject(), account.getId()));
        } catch (DataIntegrityViolationException exception) {
            // A concurrent callback may have linked the same subject. Do not issue a session from
            // an ambiguous account decision; the client must retry through the normal flow.
            throw new OidcAuthenticationFailureException(exception);
        }
        return account;
    }

    private IdentityAuthProperties.Provider provider(String providerName) {
        IdentityAuthProperties.Provider provider = properties.getOidc().provider(providerName);
        if (provider == null) {
            throw new OidcAuthenticationFailureException();
        }
        return provider;
    }

    private void validatePkceChallenge(OidcAuthorizationRequest request) {
        if (request == null || !"S256".equals(request.codeChallengeMethod())
                || request.codeChallenge() == null
                || !CODE_CHALLENGE_PATTERN.matcher(request.codeChallenge()).matches()) {
            throw new OidcAuthenticationFailureException();
        }
    }

    private boolean verifyPkce(String expectedChallenge, String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            String actualChallenge = Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
            return MessageDigest.isEqual(
                    expectedChallenge.getBytes(StandardCharsets.US_ASCII),
                    actualChallenge.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException exception) {
            throw new AuthenticationDependencyUnavailableException(exception);
        }
    }

    private String validateEmail(String email) {
        if (!hasText(email) || email.length() > MAX_EMAIL_LENGTH || !EMAIL_PATTERN.matcher(email).matches()) {
            throw new OidcAuthenticationFailureException();
        }
        return EmailAddressNormalizer.normalize(email);
    }

    private void validateProviderTransport(IdentityAuthProperties.Provider provider) {
        if (!httpsUri(provider.getIssuer())
                || !httpsUri(provider.getAuthorizationUri())
                || !httpsUri(provider.getTokenUri())
                || !httpsUri(provider.getJwkSetUri())
                || !callbackUri(provider.getRedirectUri())) {
            throw new OidcAuthenticationFailureException();
        }
    }

    private boolean httpsUri(String value) {
        try {
            return "https".equalsIgnoreCase(URI.create(value).getScheme());
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private boolean callbackUri(String value) {
        try {
            URI uri = URI.create(value);
            if ("https".equalsIgnoreCase(uri.getScheme())) {
                return true;
            }
            String host = uri.getHost();
            return "http".equalsIgnoreCase(uri.getScheme())
                    && ("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private String boundedDisplayName(String displayName, String email) {
        String value = hasText(displayName) ? displayName.trim() : email.substring(0, email.indexOf('@'));
        return value.substring(0, Math.min(MAX_DISPLAY_NAME_LENGTH, value.length()));
    }

    private String randomValue() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String browserTransactionHash(String browserTransaction) {
        if (!hasText(browserTransaction)
                || !BROWSER_TRANSACTION_PATTERN.matcher(browserTransaction).matches()) {
            return null;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(browserTransaction.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new AuthenticationDependencyUnavailableException(exception);
        }
    }

    private boolean browserTransactionMatches(String expectedHash, String actualHash) {
        return expectedHash == null || (actualHash != null && MessageDigest.isEqual(
                expectedHash.getBytes(StandardCharsets.US_ASCII),
                actualHash.getBytes(StandardCharsets.US_ASCII)));
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private void recordSuccessfulAudit(UserAccount account, String clientAddress) {
        try {
            auditService.recordWithinCurrentTransaction(
                    SecurityAuditEventType.OIDC_LOGIN_SUCCEEDED, account.getId(), clientAddress);
            Runnable committedOutcome = () -> metrics.record(SecurityAuditEventType.OIDC_LOGIN_SUCCEEDED);
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
            log.atError().addKeyValue("event", "oidc_login_audit_failed")
                    .log("OIDC authentication audit persistence failed");
            throw new AuthenticationDependencyUnavailableException(exception);
        }
    }

    private void recordAudit(SecurityAuditEventType eventType, UUID accountId, String clientAddress) {
        try {
            auditService.record(eventType, accountId, clientAddress);
            metrics.record(eventType);
        } catch (RuntimeException exception) {
            log.atError().addKeyValue("event", "oidc_callback_audit_failed")
                    .log("OIDC authentication audit persistence failed");
            throw new AuthenticationDependencyUnavailableException(exception);
        }
    }

    private record AuthorizationStart(URI authorizationUri, String state) {
    }
}
