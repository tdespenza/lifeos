package com.lifeos.identity.account;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional application service for account identity operations.
 */
@Service
public class UserAccountService {

    private final UserAccountRepository repository;
    private final AccountRegistrationIdempotencyService registrationService;

    /**
     * Creates the service with account lookup and public-registration collaborators.
     *
     * @param repository account persistence repository
     * @param registrationService durable public password-registration coordinator
     */
    public UserAccountService(
            UserAccountRepository repository,
            AccountRegistrationIdempotencyService registrationService) {
        this.repository = repository;
        this.registrationService = registrationService;
    }

    /**
     * Registers a first-party account and its password credential through the durable retry
     * coordinator. Internal/OIDC account creation intentionally does not call this method because
     * an externally verified identity is not evidence of a local password credential.
     *
     * @param email validated email address
     * @param displayName validated display name
     * @param password transient raw password
     * @param idempotencyKeyValues all received opaque client retry-key header values
     * @param clientAddress source address used only for the redacted audit fingerprint
     * @return created or safely replayed registration result
     */
    public AccountRegistrationResult register(
            String email,
            String displayName,
            String password,
            List<String> idempotencyKeyValues,
            String clientAddress) {
        return registrationService.createOrReplay(
                email, displayName, password, idempotencyKeyValues, clientAddress);
    }

    /**
     * Retrieves an account without opening a write transaction.
     *
     * @param id account UUID
     * @return the persisted account
     * @throws AccountNotFoundException when the account does not exist
     */
    @Transactional(readOnly = true)
    public UserAccount getById(UUID id) {
        return repository.findById(id)
                .orElseThrow(AccountNotFoundException::new);
    }

    /**
     * Retrieves an account while locking its row for session-capacity enforcement.
     *
     * @param id account UUID
     * @return the locked account
     * @throws AccountNotFoundException when the account does not exist
     */
    @Transactional
    public UserAccount getByIdForUpdate(UUID id) {
        return repository.findByIdForUpdate(id)
                .orElseThrow(AccountNotFoundException::new);
    }
}
