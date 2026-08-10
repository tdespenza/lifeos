package com.lifeos.identity.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

/**
 * Durable mapping from a verified provider subject to a LifeOS account.
 *
 * <p>Provider access and refresh tokens are intentionally not stored. The provider/subject pair
 * is the stable external identity key; the account email remains owned by {@code UserAccount}.
 */
@Entity
@Table(name = "external_identity", uniqueConstraints = @UniqueConstraint(
        name = "uk_external_identity_provider_subject", columnNames = {"provider", "subject"}))
public class ExternalIdentity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 64)
    private String provider;

    @Column(nullable = false, length = 255)
    private String subject;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(nullable = false, updatable = false)
    private Instant linkedAt;

    /**
     * Creates an empty entity for JPA materialization.
     */
    protected ExternalIdentity() {
        // required by JPA
    }

    /**
     * Creates a provider-subject mapping.
     *
     * @param provider configured provider name
     * @param subject verified provider subject
     * @param accountId LifeOS account UUID
     */
    public ExternalIdentity(String provider, String subject, UUID accountId) {
        this.provider = provider;
        this.subject = subject;
        this.accountId = accountId;
        this.linkedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getProvider() {
        return provider;
    }

    public String getSubject() {
        return subject;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public Instant getLinkedAt() {
        return linkedAt;
    }
}
