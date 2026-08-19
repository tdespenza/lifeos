package com.lifeos.identity.account;

import com.lifeos.identity.auth.AuthenticationDependencyUnavailableException;
import com.lifeos.identity.auth.PasswordCredential;
import com.lifeos.identity.auth.PasswordCredentialRepository;
import com.lifeos.identity.auth.PasswordVerifier;
import com.lifeos.identity.auth.SecurityAuditEventType;
import com.lifeos.identity.auth.SecurityAuditService;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transaction boundaries for durable account-registration idempotency.
 *
 * <p>The reservation commits independently. A crash after reserving a key but before account
 * creation leaves a recoverable pending row; a matching retry locks that row and safely completes
 * the original command instead of creating a second account or credential.
 */
@Service
public class AccountRegistrationIdempotencyTransactions {

    /** Bounds idempotency lock waits and public registration database work. */
    public static final int TRANSACTION_TIMEOUT_SECONDS = 5;

    private final AccountRegistrationIdempotencyRepository idempotencyRepository;
    private final UserAccountRepository accountRepository;
    private final PasswordCredentialRepository credentialRepository;
    private final PasswordVerifier passwordVerifier;
    private final SecurityAuditService auditService;

    /**
     * Creates the transaction boundary.
     *
     * @param idempotencyRepository durable registration reservation store
     * @param accountRepository account store
     * @param credentialRepository first-party password credential store
     * @param passwordVerifier bounded Argon2id operation boundary
     * @param auditService redacted audit writer
     */
    public AccountRegistrationIdempotencyTransactions(
            AccountRegistrationIdempotencyRepository idempotencyRepository,
            UserAccountRepository accountRepository,
            PasswordCredentialRepository credentialRepository,
            PasswordVerifier passwordVerifier,
            SecurityAuditService auditService) {
        this.idempotencyRepository = idempotencyRepository;
        this.accountRepository = accountRepository;
        this.credentialRepository = credentialRepository;
        this.passwordVerifier = passwordVerifier;
        this.auditService = auditService;
    }

    /**
     * Reserves one client key before account persistence begins.
     *
     * @param keyHash HMAC digest of the opaque key
     * @param requestFingerprint HMAC digest of the canonical registration payload
     * @return committed pending reservation
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = TRANSACTION_TIMEOUT_SECONDS)
    public AccountRegistrationIdempotency reserve(String keyHash, String requestFingerprint) {
        return idempotencyRepository.saveAndFlush(new AccountRegistrationIdempotency(keyHash, requestFingerprint));
    }

    /**
     * Finds a key reservation without exposing raw retry material.
     *
     * @param keyHash HMAC digest of the opaque key
     * @return matching reservation when one committed
     */
    @Transactional(readOnly = true, timeout = TRANSACTION_TIMEOUT_SECONDS)
    public Optional<AccountRegistrationIdempotency> findExisting(String keyHash) {
        return idempotencyRepository.findByIdempotencyKeyHash(keyHash);
    }

    /**
     * Completes or replays one reserved registration under its row lock.
     *
     * <p>The account, its Argon2id password credential, the completed reservation, and the
     * redacted success/replay audit event are committed atomically. A completed reservation whose
     * account is missing is a data-integrity incident and is never recreated.
     *
     * @param reservationId durable reservation identifier
     * @param requestFingerprint retry payload fingerprint
     * @param normalizedEmail canonical email address
     * @param displayName requested display name
     * @param rawPassword transient validated password
     * @param clientAddress source address used only for audit HMAC calculation
     * @return created or replayed account result
     */
    @Transactional(timeout = TRANSACTION_TIMEOUT_SECONDS)
    public AccountRegistrationResult complete(
            UUID reservationId,
            String requestFingerprint,
            String normalizedEmail,
            String displayName,
            String rawPassword,
            String clientAddress) {
        AccountRegistrationIdempotency reservation = idempotencyRepository.findByIdForUpdate(reservationId)
                .orElseThrow(AccountRegistrationIdempotencyUnavailableException::new);
        if (!reservation.matchesRequestFingerprint(requestFingerprint)) {
            throw new AccountRegistrationIdempotencyConflictException();
        }

        if (reservation.isCompleted()) {
            UUID accountId = reservation.getAccountId();
            UserAccount account = accountId == null
                    ? null
                    : accountRepository.findById(accountId).orElse(null);
            if (account == null) {
                throw new AccountRegistrationIdempotencyUnavailableException();
            }
            auditService.recordWithinCurrentTransaction(
                    SecurityAuditEventType.ACCOUNT_REGISTRATION_REPLAYED,
                    account.getId(),
                    clientAddress);
            return new AccountRegistrationResult(account, true);
        }

        if (accountRepository.existsByEmail(normalizedEmail)) {
            throw new EmailAlreadyRegisteredException();
        }

        String encodedPassword = passwordVerifier.encode(rawPassword);
        UserAccount account;
        try {
            account = accountRepository.saveAndFlush(new UserAccount(normalizedEmail, displayName));
        } catch (DataIntegrityViolationException exception) {
            // The preceding lookup is only an efficient path. The unique index remains the final
            // race-safe guard when a concurrent OIDC or registration flow creates the same email.
            throw new EmailAlreadyRegisteredException(exception);
        }
        credentialRepository.save(new PasswordCredential(account, encodedPassword));
        reservation.complete(account.getId());
        auditService.recordWithinCurrentTransaction(
                SecurityAuditEventType.ACCOUNT_REGISTRATION_SUCCEEDED,
                account.getId(),
                clientAddress);
        return new AccountRegistrationResult(account, false);
    }
}
