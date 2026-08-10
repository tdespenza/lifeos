package com.lifeos.identity.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

/**
 * Persisted account identity data owned by the identity service.
 *
 * <p>This entity represents an account identity only. Password credentials and verified external
 * provider subjects are stored in separate authentication-boundary entities.
 */
@Entity
@Table(name = "user_account", uniqueConstraints = @UniqueConstraint(name = "uk_user_account_email", columnNames = "email"))
public class UserAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String email;

    @Column(nullable = false)
    private String displayName;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private AccountStatus status = AccountStatus.ACTIVE;

    /**
     * Creates an empty entity for JPA materialization.
     */
    protected UserAccount() {
        // required by JPA
    }

    /**
     * Creates a new account identity for persistence.
     *
     * @param email account email address
     * @param displayName account display name
     */
    public UserAccount(String email, String displayName) {
        this.email = email;
        this.displayName = displayName;
        this.createdAt = Instant.now();
        this.status = AccountStatus.ACTIVE;
    }

    /**
     * Returns the stable account identifier.
     *
     * @return the generated account UUID
     */
    public UUID getId() {
        return id;
    }

    /**
     * Returns the value supplied during registration.
     *
     * @return the account email address
     */
    public String getEmail() {
        return email;
    }

    /**
     * Returns the account's display name.
     *
     * @return the display name
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * Returns the immutable account creation timestamp.
     *
     * @return the UTC creation timestamp
     */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * Returns the account lifecycle state used by authentication decisions.
     *
     * @return account status
     */
    public AccountStatus getStatus() {
        return status;
    }

    /**
     * Returns whether this account is currently allowed to authenticate.
     *
     * @return {@code true} only for active accounts
     */
    public boolean isActive() {
        return status == AccountStatus.ACTIVE;
    }

    /**
     * Disables the account without deleting its identity or related audit history.
     */
    public void disable() {
        this.status = AccountStatus.DISABLED;
    }

    /**
     * Re-enables a previously disabled account.
     */
    public void enable() {
        this.status = AccountStatus.ACTIVE;
    }
}
