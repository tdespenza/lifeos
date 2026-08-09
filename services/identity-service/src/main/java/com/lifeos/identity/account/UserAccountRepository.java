package com.lifeos.identity.account;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
