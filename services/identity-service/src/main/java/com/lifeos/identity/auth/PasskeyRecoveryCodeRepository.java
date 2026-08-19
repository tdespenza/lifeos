package com.lifeos.identity.auth;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

/** Locked access to one-time recovery-code rows. */
public interface PasskeyRecoveryCodeRepository extends JpaRepository<PasskeyRecoveryCode, UUID> {

    List<PasskeyRecoveryCode> findAllByAccount_IdAndUsedAtIsNull(UUID accountId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "1000"))
    @Query("select code from PasskeyRecoveryCode code where code.account.id = :accountId "
            + "and code.codeHash = :codeHash and code.usedAt is null")
    Optional<PasskeyRecoveryCode> findUsableForUpdate(
            @Param("accountId") UUID accountId, @Param("codeHash") String codeHash);
}
