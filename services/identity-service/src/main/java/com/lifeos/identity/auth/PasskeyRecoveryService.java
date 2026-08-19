package com.lifeos.identity.auth;

import com.lifeos.identity.account.EmailAddressNormalizer;
import com.lifeos.identity.account.UserAccount;
import com.lifeos.identity.account.UserAccountRepository;
import com.lifeos.identity.notification.IdentityRecoveryNotificationService;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Generates and consumes bounded one-time recovery codes for passkey-only account access. */
@Service
public class PasskeyRecoveryService {

    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ234567".toCharArray();

    private final PasskeyRecoveryProperties properties;
    private final UserAccountRepository accountRepository;
    private final PasskeyRecoveryCodeRepository codeRepository;
    private final LoginRateLimiter rateLimiter;
    private final SessionTokenAuthority sessionTokenAuthority;
    private final SecurityAuditService auditService;
    private final IdentityRecoveryNotificationService notificationService;
    private final HmacSha256Digest codeDigest;
    private final SecureRandom random;
    private final Clock clock;

    @Autowired
    public PasskeyRecoveryService(
            PasskeyRecoveryProperties properties,
            UserAccountRepository accountRepository,
            PasskeyRecoveryCodeRepository codeRepository,
            LoginRateLimiter rateLimiter,
            SessionTokenAuthority sessionTokenAuthority,
            SecurityAuditService auditService,
            ObjectProvider<IdentityRecoveryNotificationService> notificationServiceProvider) {
        this(properties, accountRepository, codeRepository, rateLimiter, sessionTokenAuthority,
                auditService, notificationServiceProvider.getIfAvailable(), Clock.systemUTC(), new SecureRandom());
    }

    PasskeyRecoveryService(
            PasskeyRecoveryProperties properties,
            UserAccountRepository accountRepository,
            PasskeyRecoveryCodeRepository codeRepository,
            LoginRateLimiter rateLimiter,
            SessionTokenAuthority sessionTokenAuthority,
            SecurityAuditService auditService,
            Clock clock,
            SecureRandom random) {
        this(properties, accountRepository, codeRepository, rateLimiter, sessionTokenAuthority,
                auditService, null, clock, random);
    }

    PasskeyRecoveryService(
            PasskeyRecoveryProperties properties,
            UserAccountRepository accountRepository,
            PasskeyRecoveryCodeRepository codeRepository,
            LoginRateLimiter rateLimiter,
            SessionTokenAuthority sessionTokenAuthority,
            SecurityAuditService auditService,
            IdentityRecoveryNotificationService notificationService,
            Clock clock,
            SecureRandom random) {
        this.properties = properties;
        this.accountRepository = accountRepository;
        this.codeRepository = codeRepository;
        this.rateLimiter = rateLimiter;
        this.sessionTokenAuthority = sessionTokenAuthority;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.codeDigest = new HmacSha256Digest(properties.getHmacSecret(), "IDENTITY_PASSKEY_RECOVERY_SECRET");
        this.clock = clock;
        this.random = random;
    }

    @Transactional(timeout = 5)
    public PasskeyRecoveryResult generate(AuthenticatedSubject subject, String clientAddress) {
        if (subject == null) {
            throw new AuthenticationFailureException();
        }
        UserAccount account = accountRepository.findByIdForUpdate(subject.accountId())
                .filter(UserAccount::isActive)
                .orElseThrow(AuthenticationFailureException::new);
        Instant now = clock.instant();
        codeRepository.findAllByAccount_IdAndUsedAtIsNull(account.getId()).forEach(code -> {
            if (code.isUsable(now)) {
                code.consume(now);
            }
        });
        Instant expiresAt = now.plus(properties.getCodeTtl());
        List<String> codes = new ArrayList<>(properties.getCodeCount());
        for (int index = 0; index < properties.getCodeCount(); index++) {
            String code = nextCode();
            codes.add(code);
            codeRepository.save(new PasskeyRecoveryCode(account, codeDigest.digest(code), now, expiresAt));
        }
        codeRepository.flush();
        auditService.recordWithinCurrentTransaction(
                SecurityAuditEventType.PASSKEY_RECOVERY_CODES_ISSUED, account.getId(), clientAddress);
        enqueueCodesIssued(account.getId());
        return new PasskeyRecoveryResult(List.copyOf(codes), expiresAt);
    }

    @Transactional(timeout = 5)
    public LoginResponse recover(
            PasskeyRecoveryRequest request, String clientAddress, DeviceMetadata deviceMetadata) {
        String normalizedEmail = EmailAddressNormalizer.normalize(request.email());
        try {
            rateLimiter.check(normalizedEmail, clientAddress);
        } catch (LoginRateLimitExceededException exception) {
            auditService.record(SecurityAuditEventType.PASSKEY_RECOVERY_RATE_LIMITED, null, clientAddress);
            throw exception;
        } catch (AuthenticationDependencyUnavailableException exception) {
            auditService.record(SecurityAuditEventType.PASSKEY_RECOVERY_DEPENDENCY_UNAVAILABLE, null, clientAddress);
            throw exception;
        }
        UserAccount account = accountRepository.findByEmail(normalizedEmail)
                .filter(UserAccount::isActive)
                .orElseThrow(() -> rejected(clientAddress, null));
        PasskeyRecoveryCode code = codeRepository.findUsableForUpdate(account.getId(), codeDigest.digest(request.code()))
                .orElseThrow(() -> rejected(clientAddress, account.getId()));
        Instant now = clock.instant();
        if (!code.isUsable(now)) {
            throw rejected(clientAddress, account.getId());
        }
        code.consume(now);
        codeRepository.saveAndFlush(code);
        try {
            LoginResponse response = sessionTokenAuthority.createSession(
                    account, SessionAuthenticationMethod.PASSKEY,
                    deviceMetadata == null ? DeviceMetadata.unknown() : deviceMetadata);
            auditService.recordWithinCurrentTransaction(
                    SecurityAuditEventType.PASSKEY_RECOVERY_SUCCEEDED, account.getId(), clientAddress);
            enqueueRecoverySucceeded(account.getId());
            return response;
        } catch (SessionCapacityExceededException | AuthenticationFailureException exception) {
            auditService.record(SecurityAuditEventType.PASSKEY_RECOVERY_REJECTED, account.getId(), clientAddress);
            throw exception;
        } catch (AuthenticationDependencyUnavailableException exception) {
            auditService.record(SecurityAuditEventType.PASSKEY_RECOVERY_DEPENDENCY_UNAVAILABLE, account.getId(), clientAddress);
            throw exception;
        } catch (RuntimeException exception) {
            auditService.record(SecurityAuditEventType.PASSKEY_RECOVERY_DEPENDENCY_UNAVAILABLE, account.getId(), clientAddress);
            throw new AuthenticationDependencyUnavailableException(exception);
        }
    }

    private AuthenticationFailureException rejected(String clientAddress, UUID accountId) {
        try {
            auditService.record(SecurityAuditEventType.PASSKEY_RECOVERY_REJECTED, accountId, clientAddress);
        } catch (RuntimeException exception) {
            throw new AuthenticationDependencyUnavailableException(exception);
        }
        return new AuthenticationFailureException();
    }

    private void enqueueCodesIssued(UUID accountId) {
        if (notificationService != null) {
            notificationService.enqueueCodesIssued(accountId);
        }
    }

    private void enqueueRecoverySucceeded(UUID accountId) {
        if (notificationService != null) {
            notificationService.enqueueRecoverySucceeded(accountId);
        }
    }

    private String nextCode() {
        StringBuilder value = new StringBuilder(14);
        for (int index = 0; index < 12; index++) {
            if (index > 0 && index % 4 == 0) {
                value.append('-');
            }
            value.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return value.toString();
    }
}
