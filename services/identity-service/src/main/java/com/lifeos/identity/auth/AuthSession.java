package com.lifeos.identity.auth;

import com.lifeos.identity.account.UserAccount;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.Objects;
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

    /**
     * Authentication factor that established this session. The column stays nullable during the
     * rolling upgrade; pre-existing sessions are interpreted as password sessions by the getter.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "authentication_method", length = 16, updatable = false)
    private SessionAuthenticationMethod authenticationMethod;

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
     * @param authenticationMethod verified factor that established this session
     * @param accessTokenHash SHA-256 digest of the issued access token
     * @param createdAt creation timestamp
     * @param expiresAt expiry timestamp
     */
    public AuthSession(UUID sessionId, UserAccount account, SessionAuthenticationMethod authenticationMethod,
            String accessTokenHash,
            Instant createdAt, Instant expiresAt) {
        this.id = sessionId;
        this.accountId = account.getId();
        this.authenticationMethod = Objects.requireNonNull(authenticationMethod, "authenticationMethod");
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
     * Returns the factor that established this session.
     *
     * <p>Rows created before authentication-method persistence are password sessions because that
     * was the only supported authentication flow at the time.
     *
     * @return session authentication method
     */
    public SessionAuthenticationMethod getAuthenticationMethod() {
        return authenticationMethod == null ? SessionAuthenticationMethod.PASSWORD : authenticationMethod;
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
     * Returns the persisted digest for protected-service session validation.
     *
     * @return access-token digest, never the raw token
     */
    public String getAccessTokenHash() {
        return accessTokenHash;
    }

    /**
     * Replaces the digest when a refresh rotation issues a successor access token for this stable
     * session. The raw token is never accepted by this entity.
     *
     * @param newAccessTokenHash SHA-256 digest of the successor token
     */
    public void replaceAccessTokenHash(String newAccessTokenHash) {
        this.accessTokenHash = Objects.requireNonNull(newAccessTokenHash, "newAccessTokenHash");
    }

    /**
     * Advances the durable session deadline when refresh rotates the access token. The deadline
     * remains bounded by the refresh-family policy enforced by the refresh authority.
     *
     * @param newExpiresAt successor access-token expiry
     */
    public void extendExpiresAt(Instant newExpiresAt) {
        Objects.requireNonNull(newExpiresAt, "newExpiresAt");
        if (newExpiresAt.isAfter(this.expiresAt)) {
            this.expiresAt = newExpiresAt;
        }
    }

    /**
     * Marks the session revoked; revocation is monotonic.
     */
    public void revoke() {
        this.revoked = true;
    }
}
