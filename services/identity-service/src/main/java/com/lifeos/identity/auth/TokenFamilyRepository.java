package com.lifeos.identity.auth;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

/** Durable token-family lookups and row locking. */
public interface TokenFamilyRepository extends JpaRepository<TokenFamily, UUID> {

    /**
     * Finds the family holding the active credential digest.
     *
     * @param activeTokenHash active token digest
     * @return matching family, if present
     */
    Optional<TokenFamily> findByActiveTokenHash(String activeTokenHash);

    /**
     * Acquires a non-blocking row lock for refresh rotation.
     *
     * @param id token-family identifier
     * @return locked family, if present
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "0"))
    @Query("select family from TokenFamily family where family.id = :id")
    Optional<TokenFamily> findByIdForUpdate(@Param("id") UUID id);
}
