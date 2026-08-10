package com.lifeos.identity.auth;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence operations for verified external identities.
 */
public interface ExternalIdentityRepository extends JpaRepository<ExternalIdentity, UUID> {

    /**
     * Finds the account mapping for one provider subject.
     *
     * @param provider configured provider name
     * @param subject provider subject
     * @return matching external identity, if linked
     */
    Optional<ExternalIdentity> findByProviderAndSubject(String provider, String subject);
}
