package com.lifeos.identity.auth;

import com.lifeos.identity.account.EmailAddressNormalizer;
import com.lifeos.identity.account.UserAccount;
import com.lifeos.identity.account.UserAccountRepository;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates first-party email/password authentication.
 *
 * <p>Every credential branch performs an equivalent password-encoder operation where possible and
 * returns a sanitized failure. This service never logs or returns the submitted password.
 */
@Service
public class LoginService {

    private static final Logger log = LoggerFactory.getLogger(LoginService.class);
    private static final String DUMMY_PASSWORD = "lifeos-dummy-password-for-timing-equivalence";

    private final UserAccountRepository accountRepository;
    private final PasswordCredentialRepository credentialRepository;
    private final PasswordEncoder passwordEncoder;
    private final LoginRateLimiter rateLimiter;
    private final SessionTokenAuthority sessionTokenAuthority;
    private final SecurityAuditService auditService;
    private final LoginMetrics metrics;
    private final PasswordVerifier passwordVerifier;
    private final String dummyEncodedPassword;

    /**
     * Creates the login service and derives one in-memory dummy hash for unknown-account checks.
     *
     * @param accountRepository account repository
     * @param credentialRepository password-credential repository
     * @param passwordEncoder Argon2id encoder used to derive the timing-equivalent dummy hash
     * @param rateLimiter distributed rate limiter
     * @param sessionTokenAuthority shared session/token authority
     * @param auditService security audit service
     * @param metrics low-cardinality authentication metrics
     * @param passwordVerifier bounded Argon2id verification service
     */
    public LoginService(
            UserAccountRepository accountRepository,
            PasswordCredentialRepository credentialRepository,
            PasswordEncoder passwordEncoder,
            LoginRateLimiter rateLimiter,
            SessionTokenAuthority sessionTokenAuthority,
            SecurityAuditService auditService,
            LoginMetrics metrics,
            PasswordVerifier passwordVerifier) {
        this.accountRepository = accountRepository;
        this.credentialRepository = credentialRepository;
        this.passwordEncoder = passwordEncoder;
        this.rateLimiter = rateLimiter;
        this.sessionTokenAuthority = sessionTokenAuthority;
        this.auditService = auditService;
        this.metrics = metrics;
        this.passwordVerifier = passwordVerifier;
        this.dummyEncodedPassword = passwordEncoder.encode(DUMMY_PASSWORD);
    }

    /**
     * Applies rate limiting, verifies the password, creates a session, and audits the outcome.
     *
     * @param request validated login request
     * @param clientAddress source address used only for bounded hashed controls
     * @return signed access-token result
     * @throws AuthenticationFailureException for all credential failures
     * @throws LoginRateLimitExceededException when the attempt threshold is exceeded
     * @throws AuthenticationDependencyUnavailableException when safe authentication is impossible
     * @throws SessionCapacityExceededException when account session capacity is reached
     */
    @Transactional
    public LoginResponse login(LoginRequest request, String clientAddress) {
        String normalizedEmail = EmailAddressNormalizer.normalize(request.email());
        try {
            rateLimiter.check(normalizedEmail, clientAddress);
        } catch (LoginRateLimitExceededException exception) {
            recordAudit(SecurityAuditEventType.LOGIN_RATE_LIMITED, null, clientAddress);
            throw exception;
        } catch (AuthenticationDependencyUnavailableException exception) {
            recordAudit(SecurityAuditEventType.LOGIN_DEPENDENCY_UNAVAILABLE, null, clientAddress);
            throw exception;
        }

        Optional<UserAccount> account;
        Optional<PasswordCredential> credential;
        try {
            account = accountRepository.findByEmail(normalizedEmail);
            credential = account.flatMap(value -> credentialRepository.findByAccountId(value.getId()));
        } catch (DataAccessException exception) {
            recordAudit(SecurityAuditEventType.LOGIN_DEPENDENCY_UNAVAILABLE, null, clientAddress);
            throw new AuthenticationDependencyUnavailableException(exception);
        }
        boolean passwordMatches;
        try {
            passwordMatches = passwordVerifier.matches(
                    request.password(), credential.map(PasswordCredential::getEncodedPassword)
                            .orElse(dummyEncodedPassword));
        } catch (AuthenticationDependencyUnavailableException exception) {
            recordAudit(SecurityAuditEventType.LOGIN_DEPENDENCY_UNAVAILABLE,
                    account.map(UserAccount::getId).orElse(null), clientAddress);
            throw exception;
        }

        if (account.isEmpty() || credential.isEmpty() || !credential.get().isActive()
                || !account.get().isActive() || !passwordMatches) {
            recordAudit(SecurityAuditEventType.LOGIN_FAILED, account.map(UserAccount::getId).orElse(null), clientAddress);
            log.atInfo()
                    .addKeyValue("event", "login_failed")
                    .log("Authentication attempt rejected");
            throw new AuthenticationFailureException();
        }

        try {
            LoginResponse response = sessionTokenAuthority.createSession(account.get());
            recordAudit(SecurityAuditEventType.LOGIN_SUCCEEDED, account.get().getId(), clientAddress);
            log.atInfo()
                    .addKeyValue("event", "login_succeeded")
                    .log("Authentication succeeded");
            return response;
        } catch (SessionCapacityExceededException exception) {
            recordAudit(SecurityAuditEventType.LOGIN_SESSION_CAPACITY_REACHED,
                    account.get().getId(), clientAddress);
            throw exception;
        }
    }

    /**
     * Records an audit outcome while retaining the sanitized public error contract.
     *
     * @param eventType security event type
     * @param accountId known account, or {@code null}
     * @param clientAddress raw address held only for digesting
     */
    private void recordAudit(SecurityAuditEventType eventType, java.util.UUID accountId, String clientAddress) {
        try {
            auditService.record(eventType, accountId, clientAddress);
            metrics.record(eventType);
        } catch (RuntimeException exception) {
            log.atError()
                    .addKeyValue("event", "login_audit_failed")
                    .log("Authentication audit persistence failed", exception);
            throw new AuthenticationDependencyUnavailableException(exception);
        }
    }
}
