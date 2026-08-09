package com.lifeos.identity.account;

import java.util.NoSuchElementException;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional application service for account identity operations.
 */
@Service
public class UserAccountService {

    private static final Logger log = LoggerFactory.getLogger(UserAccountService.class);

    private final UserAccountRepository repository;

    /**
     * Creates the service with its account repository.
     *
     * @param repository account persistence repository
     */
    public UserAccountService(UserAccountRepository repository) {
        this.repository = repository;
    }

    /**
     * Registers an account while relying on the database unique constraint as the final race-safe
     * guard against duplicate email addresses.
     *
     * @param email validated email address
     * @param displayName validated display name
     * @return the newly persisted account
     * @throws EmailAlreadyRegisteredException when the email is already registered, including when
     *         a concurrent registration wins the database race
     */
    @Transactional
    public UserAccount register(String email, String displayName) {
        if (repository.existsByEmail(email)) {
            logRegistrationConflict();
            throw new EmailAlreadyRegisteredException();
        }
        try {
            UserAccount account = repository.saveAndFlush(new UserAccount(email, displayName));
            log.atInfo()
                    .addKeyValue("event", "account_registration_succeeded")
                    .log("Account registration succeeded");
            return account;
        } catch (DataIntegrityViolationException exception) {
            if (isEmailUniqueConstraintViolation(exception)) {
                logRegistrationConflict();
                throw new EmailAlreadyRegisteredException(exception);
            }
            throw exception;
        }
    }

    /**
     * Records a duplicate-registration event without logging the submitted email address.
     */
    private void logRegistrationConflict() {
        log.atInfo()
                .addKeyValue("event", "account_registration_conflict")
                .log("Account registration rejected because the email is already registered");
    }

    /**
     * Determines whether a data-integrity failure came from the account email constraint.
     *
     * @param exception persistence failure to inspect
     * @return {@code true} when the named email uniqueness constraint was violated
     */
    private boolean isEmailUniqueConstraintViolation(DataIntegrityViolationException exception) {
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof ConstraintViolationException constraintViolation) {
                return "uk_user_account_email".equals(constraintViolation.getConstraintName());
            }
            cause = cause.getCause();
        }
        return false;
    }

    /**
     * Retrieves an account without opening a write transaction.
     *
     * @param id account UUID
     * @return the persisted account
     * @throws NoSuchElementException when the account does not exist
     */
    @Transactional(readOnly = true)
    public UserAccount getById(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("No account with id: " + id));
    }
}
