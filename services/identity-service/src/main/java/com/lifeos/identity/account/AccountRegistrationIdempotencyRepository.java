package com.lifeos.identity.account;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

/** Database operations that make public account registration replay-safe across instances. */
public interface AccountRegistrationIdempotencyRepository
        extends JpaRepository<AccountRegistrationIdempotency, UUID> {

    Optional<AccountRegistrationIdempotency> findByIdempotencyKeyHash(String idempotencyKeyHash);

    /**
     * Locks one reservation while its account is inspected or created.
     *
     * <p>A finite lock timeout prevents a stalled peer transaction from retaining a public request
     * thread indefinitely.
     *
     * @param recordId reservation identifier
     * @return locked reservation when it exists
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("select record from AccountRegistrationIdempotency record where record.id = :recordId")
    Optional<AccountRegistrationIdempotency> findByIdForUpdate(@Param("recordId") UUID recordId);
}
