package com.lifeos.identity.auth;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Replay-evidence persistence for consumed refresh credentials. */
public interface ConsumedRefreshTokenRepository extends JpaRepository<ConsumedRefreshToken, UUID> {

    Optional<ConsumedRefreshToken> findByTokenHash(String tokenHash);

    long countByFamilyId(UUID familyId);
}
