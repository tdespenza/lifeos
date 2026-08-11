package com.lifeos.identity.auth;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistence operations for WebAuthn credential metadata.
 */
public interface WebAuthnCredentialRepository extends JpaRepository<WebAuthnCredential, UUID> {

    /**
     * Finds an enabled credential by its authenticator identifier.
     *
     * @param credentialId URL-safe credential identifier
     * @return enabled credential, when present
     */
    Optional<WebAuthnCredential> findByCredentialIdAndEnabledTrue(String credentialId);

    /**
     * Finds an enabled credential by its discoverable user handle.
     *
     * @param userHandle URL-safe user handle
     * @return enabled credentials, possibly empty when no credential uses the handle
     */
    List<WebAuthnCredential> findByUserHandleAndEnabledTrue(String userHandle);

    /**
     * Lists enabled credentials owned by one account.
     *
     * @param accountId account UUID
     * @return enabled credentials
     */
    List<WebAuthnCredential> findAllByAccount_IdAndEnabledTrue(UUID accountId);

    /**
     * Advances the signature counter only if the value observed during verification is still
     * current. This compare-and-set prevents two concurrent valid assertions from both advancing
     * one non-zero authenticator counter.
     *
     * @param id credential row UUID
     * @param expectedCount counter used by WebAuthn verification
     * @param nextCount counter returned by the authenticator
     * @param lastUsedAt last successful-use timestamp
     * @return number of rows updated; exactly one is required
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update WebAuthnCredential credential set credential.signatureCount = :nextCount, "
            + "credential.lastUsedAt = :lastUsedAt "
            + "where credential.id = :id and credential.enabled = true "
            + "and credential.signatureCount = :expectedCount")
    int advanceSignatureCountIfCurrent(
            @Param("id") UUID id,
            @Param("expectedCount") long expectedCount,
            @Param("nextCount") long nextCount,
            @Param("lastUsedAt") Instant lastUsedAt);
}
