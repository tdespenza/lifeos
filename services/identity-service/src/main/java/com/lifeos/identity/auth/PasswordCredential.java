package com.lifeos.identity.auth;

import com.lifeos.identity.account.UserAccount;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

/**
 * Durable first-party password credential owned by the identity service.
 *
 * <p>The entity stores only an Argon2id encoded password value. Raw passwords, reversible
 * encryption, and password material in logs are deliberately outside this model.
 */
@Entity
@Table(name = "password_credential", uniqueConstraints = @UniqueConstraint(
        name = "uk_password_credential_account", columnNames = "account_id"))
public class PasswordCredential {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private UserAccount account;

    @Column(name = "encoded_password", nullable = false, length = 512)
    private String encodedPassword;

    @Column(nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private PasswordCredentialStatus status = PasswordCredentialStatus.ACTIVE;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    /**
     * Creates an empty entity for JPA materialization.
     */
    protected PasswordCredential() {
        // required by JPA
    }

    /**
     * Creates an active credential from an already encoded password.
     *
     * @param account owning account
     * @param encodedPassword Argon2id encoded password; never a raw password
     */
    public PasswordCredential(UserAccount account, String encodedPassword) {
        if (account == null) {
            throw new IllegalArgumentException("Account must not be null");
        }
        if (encodedPassword == null || encodedPassword.isBlank()) {
            throw new IllegalArgumentException("Encoded password must not be blank");
        }
        this.account = account;
        this.encodedPassword = encodedPassword;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    /**
     * Returns the stable credential identifier.
     *
     * @return credential UUID
     */
    public UUID getId() {
        return id;
    }

    /**
     * Returns the owning account.
     *
     * @return account identity
     */
    public UserAccount getAccount() {
        return account;
    }

    /**
     * Returns the encoded password for the password encoder only.
     *
     * @return Argon2id encoded password
     */
    public String getEncodedPassword() {
        return encodedPassword;
    }

    /**
     * Returns the credential lifecycle state.
     *
     * @return credential status
     */
    public PasswordCredentialStatus getStatus() {
        return status;
    }

    /**
     * Returns whether the credential is eligible for authentication.
     *
     * @return {@code true} when the credential is active
     */
    public boolean isActive() {
        return status == PasswordCredentialStatus.ACTIVE;
    }

    /**
     * Replaces the encoded password during a controlled credential rotation.
     *
     * @param replacement encoded Argon2id password
     */
    public void replaceEncodedPassword(String replacement) {
        if (replacement == null || replacement.isBlank()) {
            throw new IllegalArgumentException("Encoded password must not be blank");
        }
        this.encodedPassword = replacement;
        this.status = PasswordCredentialStatus.ACTIVE;
        this.updatedAt = Instant.now();
    }

    /**
     * Disables this credential without deleting audit-relevant history.
     */
    public void disable() {
        this.status = PasswordCredentialStatus.DISABLED;
        this.updatedAt = Instant.now();
    }

    /**
     * Permanently revokes this credential.
     */
    public void revoke() {
        this.status = PasswordCredentialStatus.REVOKED;
        this.updatedAt = Instant.now();
    }
}
