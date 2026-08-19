package com.lifeos.identity.account;

import com.lifeos.identity.auth.AuthenticationDependencyUnavailableException;
import com.lifeos.identity.auth.RegistrationIdempotencyFingerprint;
import com.lifeos.identity.auth.SecurityAuditEventType;
import com.lifeos.identity.auth.SecurityAuditService;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionTimedOutException;

/** Coordinates public-registration validation, durable reservations, and safe replay behavior. */
@Service
public class AccountRegistrationIdempotencyService {

    private final AccountRegistrationIdempotencyTransactions transactions;
    private final RegistrationIdempotencyFingerprint fingerprint;
    private final SecurityAuditService auditService;

    /**
     * Creates the idempotency coordinator.
     *
     * @param transactions explicit reservation/completion transaction boundaries
     * @param fingerprint secret-backed key and request digest service
     * @param auditService redacted independent audit writer for rejected attempts
     */
    public AccountRegistrationIdempotencyService(
            AccountRegistrationIdempotencyTransactions transactions,
            RegistrationIdempotencyFingerprint fingerprint,
            SecurityAuditService auditService) {
        this.transactions = transactions;
        this.fingerprint = fingerprint;
        this.auditService = auditService;
    }

    /**
     * Creates or replays one public account registration.
     *
     * @param email submitted email address
     * @param displayName submitted display name
     * @param rawPassword transient validated password
     * @param idempotencyKeyValues all values received for the opaque client retry-key header
     * @param clientAddress source address used only for audit HMAC calculation
     * @return created or replayed account result
     */
    public AccountRegistrationResult createOrReplay(
            String email,
            String displayName,
            String rawPassword,
            List<String> idempotencyKeyValues,
            String clientAddress) {
        String key;
        try {
            RegistrationPasswordPolicy.requireValid(rawPassword);
            key = AccountRegistrationIdempotencyKey.requireSingleHeader(idempotencyKeyValues);
        } catch (InvalidRegistrationPasswordException | InvalidAccountRegistrationIdempotencyKeyException exception) {
            recordRejected(clientAddress);
            throw exception;
        }
        String normalizedEmail = EmailAddressNormalizer.normalize(email);
        String keyHash = fingerprint.keyHash(key);
        String requestFingerprint = fingerprint.requestFingerprint(normalizedEmail, displayName, rawPassword);
        AccountRegistrationIdempotency reservation = reserveOrLoad(keyHash, requestFingerprint, clientAddress);

        if (!reservation.matchesRequestFingerprint(requestFingerprint)) {
            recordRejected(clientAddress);
            throw new AccountRegistrationIdempotencyConflictException();
        }

        try {
            return transactions.complete(
                    reservation.getId(),
                    requestFingerprint,
                    normalizedEmail,
                    displayName,
                    rawPassword,
                    clientAddress);
        } catch (EmailAlreadyRegisteredException | AccountRegistrationIdempotencyConflictException exception) {
            recordRejected(clientAddress);
            throw exception;
        } catch (AuthenticationDependencyUnavailableException exception) {
            recordDependencyUnavailable(clientAddress);
            throw exception;
        } catch (DataAccessException | TransactionTimedOutException exception) {
            recordDependencyUnavailable(clientAddress);
            throw new AccountRegistrationIdempotencyUnavailableException();
        }
    }

    private AccountRegistrationIdempotency reserveOrLoad(
            String keyHash, String requestFingerprint, String clientAddress) {
        Optional<AccountRegistrationIdempotency> existing = findExisting(keyHash, clientAddress);
        if (existing.isPresent()) {
            return existing.get();
        }
        try {
            return transactions.reserve(keyHash, requestFingerprint);
        } catch (DataIntegrityViolationException exception) {
            // Concurrent first submissions may both miss the read. The unique index gives one
            // winner; loading its committed reservation in a fresh transaction converges retries.
            return findExisting(keyHash, clientAddress)
                    .orElseThrow(AccountRegistrationIdempotencyUnavailableException::new);
        } catch (DataAccessException | TransactionTimedOutException exception) {
            recordDependencyUnavailable(clientAddress);
            throw new AccountRegistrationIdempotencyUnavailableException();
        }
    }

    private Optional<AccountRegistrationIdempotency> findExisting(String keyHash, String clientAddress) {
        try {
            return transactions.findExisting(keyHash);
        } catch (DataAccessException | TransactionTimedOutException exception) {
            recordDependencyUnavailable(clientAddress);
            throw new AccountRegistrationIdempotencyUnavailableException();
        }
    }

    private void recordRejected(String clientAddress) {
        record(SecurityAuditEventType.ACCOUNT_REGISTRATION_REJECTED, clientAddress);
    }

    private void recordDependencyUnavailable(String clientAddress) {
        record(SecurityAuditEventType.ACCOUNT_REGISTRATION_DEPENDENCY_UNAVAILABLE, clientAddress);
    }

    private void record(SecurityAuditEventType eventType, String clientAddress) {
        try {
            auditService.record(eventType, null, clientAddress);
        } catch (RuntimeException exception) {
            throw new AuthenticationDependencyUnavailableException(exception);
        }
    }
}
