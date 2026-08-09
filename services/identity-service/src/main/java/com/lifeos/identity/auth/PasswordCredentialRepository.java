package com.lifeos.identity.auth;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence operations for first-party password credentials.
 */
public interface PasswordCredentialRepository extends JpaRepository<PasswordCredential, UUID> {

    /**
     * Finds the single password credential owned by an account.
     *
     * @param accountId account UUID
     * @return credential when one has been provisioned
     */
    Optional<PasswordCredential> findByAccountId(UUID accountId);
}
