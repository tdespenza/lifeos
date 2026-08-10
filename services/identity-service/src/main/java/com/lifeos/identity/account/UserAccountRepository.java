package com.lifeos.identity.account;

import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

/**
 * Persistence operations for accounts owned by the identity service.
 */
public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {

    /**
     * Finds an account by its unique email address.
     *
     * @param email email address to look up
     * @return the matching account, when present
     */
    Optional<UserAccount> findByEmail(String email);

    /**
     * Checks whether an account already uses an email address.
     *
     * @param email email address to check
     * @return {@code true} when the email is already registered
     */
    boolean existsByEmail(String email);

    /**
     * Finds an account while holding a database row lock for bounded session-capacity updates.
     *
     * @param id account UUID
     * @return locked account when present
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "1000"))
    @Query("select account from UserAccount account where account.id = :id")
    Optional<UserAccount> findByIdForUpdate(@Param("id") UUID id);
}
