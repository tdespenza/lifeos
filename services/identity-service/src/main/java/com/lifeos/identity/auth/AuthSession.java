package com.lifeos.identity.auth;

import com.lifeos.identity.account.UserAccount;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

/**
 * Durable session metadata used as the identity service's revocation authority.
 *
 * <p>The raw bearer token is never stored. Only its SHA-256 digest is persisted so a database
 * disclosure does not directly become a usable session.
 */
@Entity
@Table(name = "auth_session", uniqueConstraints = @UniqueConstraint(
        name = "uk_auth_session_token_hash", columnNames = "access_token_hash"))
public class AuthSession {

    @Id
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "access_token_hash", nullable = false, length = 64)
    private String accessTokenHash;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private boolean revoked;

    /**
     * Creates an empty entity for JPA materialization.
     */
    protected AuthSession() {
        // required by JPA
    }

    /**
     * Creates an active durable session record.
     *
     * @param sessionId stable session identifier
     * @param account owning account
     * @param accessTokenHash SHA-256 digest of the issued access token
     * @param createdAt creation timestamp
     * @param expiresAt expiry timestamp
     */
    public AuthSession(UUID sessionId, UserAccount account, String accessTokenHash,
            Instant createdAt, Instant expiresAt) {
        this.id = sessionId;
        this.accountId = account.getId();
        this.accessTokenHash = accessTokenHash;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.revoked = false;
    }

    /**
     * Returns the session identifier.
     *
     * @return session UUID
     */
    public UUID getId() {
        return id;
    }

    /**
     * Returns the account UUID that owns this session.
     *
     * @return account UUID
     */
    public UUID getAccountId() {
        return accountId;
    }

    /**
     * Returns whether the session has been explicitly revoked.
     *
     * @return {@code true} when revoked
     */
    public boolean isRevoked() {
        return revoked;
    }

    /**
     * Returns the session expiry instant.
     *
     * @return expiry timestamp
     */
    public Instant getExpiresAt() {
        return expiresAt;
    }

    /**
     * Marks the session revoked; revocation is monotonic.
     */
    public void revoke() {
        this.revoked = true;
    }
}
