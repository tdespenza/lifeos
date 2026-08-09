package com.lifeos.identity.account;

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
 * Persisted account-registration data owned by the identity service.
 *
 * <p>This entity represents an account identity only. It deliberately does not store credentials
 * until the authentication design and security boundary are implemented.
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
}
