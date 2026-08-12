package com.lifeos.identity.auth;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Durable token-family lookups and row locking. */
public interface TokenFamilyRepository extends JpaRepository<TokenFamily, UUID> {

    Optional<TokenFamily> findByActiveTokenHash(String activeTokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select family from TokenFamily family where family.id = :id")
    Optional<TokenFamily> findByIdForUpdate(@Param("id") UUID id);
}
