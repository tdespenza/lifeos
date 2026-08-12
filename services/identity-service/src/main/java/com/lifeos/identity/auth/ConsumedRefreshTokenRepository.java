package com.lifeos.identity.auth;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Replay-evidence persistence for consumed refresh credentials. */
public interface ConsumedRefreshTokenRepository extends JpaRepository<ConsumedRefreshToken, UUID> {

    /**
     * Finds replay evidence by its unique token digest.
     *
     * @param tokenHash consumed token digest
     * @return matching evidence, if present
     */
    Optional<ConsumedRefreshToken> findByTokenHash(String tokenHash);

    /**
     * Counts retained replay evidence for one family.
     *
     * @param familyId token family identifier
     * @return evidence count
     */
    long countByFamilyId(UUID familyId);
}
