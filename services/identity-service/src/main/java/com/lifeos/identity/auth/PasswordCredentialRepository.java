package com.lifeos.identity.auth;

import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * Finds a credential while holding its row lock during session creation.
     *
     * @param accountId owning account UUID
     * @return locked credential when one has been provisioned
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select credential from PasswordCredential credential "
            + "where credential.account.id = :accountId")
    Optional<PasswordCredential> findByAccountIdForUpdate(@Param("accountId") UUID accountId);
}
