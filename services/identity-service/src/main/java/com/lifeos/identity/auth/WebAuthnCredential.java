package com.lifeos.identity.auth;

import com.lifeos.identity.account.UserAccount;
import com.yubico.webauthn.data.ByteArray;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

/**
 * Durable metadata for one WebAuthn credential.
 *
 * <p>The identity service stores the credential public key and authenticator metadata only. The
 * private key remains inside the user's authenticator and is never accepted by this entity or any
 * authentication endpoint.
 */
@Entity
@Table(name = "webauthn_credential", uniqueConstraints = {
    @UniqueConstraint(name = "uk_webauthn_credential_id", columnNames = "credential_id"),
    @UniqueConstraint(name = "uk_webauthn_user_handle", columnNames = "user_handle")
})
public class WebAuthnCredential {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private UserAccount account;

    @Column(name = "credential_id", nullable = false, length = 1024)
    private String credentialId;

    @Column(name = "user_handle", nullable = false, length = 512)
    private String userHandle;

    @Column(name = "public_key_cose", nullable = false, length = 8192)
    private String publicKeyCose;

    @Column(name = "signature_count", nullable = false)
    private long signatureCount;

    @Column(nullable = false)
    private boolean enabled;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column
    private Instant lastUsedAt;

    /**
     * Creates an empty entity for JPA materialization.
     */
    protected WebAuthnCredential() {
        // required by JPA
    }

    /**
     * Creates active credential metadata from a successful WebAuthn registration result.
     *
     * @param account owning LifeOS account
     * @param credentialId authenticator credential identifier
     * @param userHandle discoverable-credential user handle
     * @param publicKeyCose credential public key in COSE encoding
     * @param signatureCount authenticator signature counter
     */
    public WebAuthnCredential(
            UserAccount account,
            ByteArray credentialId,
            ByteArray userHandle,
            ByteArray publicKeyCose,
            long signatureCount) {
        if (account == null || credentialId == null || userHandle == null || publicKeyCose == null) {
            throw new IllegalArgumentException("WebAuthn credential fields must not be null");
        }
        if (credentialId.isEmpty() || userHandle.isEmpty() || publicKeyCose.isEmpty()) {
            throw new IllegalArgumentException("WebAuthn credential fields must not be empty");
        }
        if (signatureCount < 0) {
            throw new IllegalArgumentException("signatureCount must not be negative");
        }
        this.account = account;
        this.credentialId = credentialId.getBase64Url();
        this.userHandle = userHandle.getBase64Url();
        this.publicKeyCose = publicKeyCose.getBase64();
        this.signatureCount = signatureCount;
        this.enabled = true;
        this.createdAt = Instant.now();
    }

    /**
     * Returns the stable credential row identifier.
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
     * Returns the URL-safe credential identifier.
     *
     * @return credential ID
     */
    public String getCredentialId() {
        return credentialId;
    }

    /**
     * Returns the URL-safe discoverable-credential user handle.
     *
     * @return user handle
     */
    public String getUserHandle() {
        return userHandle;
    }

    /**
     * Returns the standard Base64-encoded COSE public key.
     *
     * @return public key COSE bytes
     */
    public String getPublicKeyCose() {
        return publicKeyCose;
    }

    /**
     * Returns the last persisted authenticator signature counter.
     *
     * @return signature counter
     */
    public long getSignatureCount() {
        return signatureCount;
    }

    /**
     * Returns whether the credential may authenticate.
     *
     * @return true for an enabled credential
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Returns the credential registration time.
     *
     * @return registration timestamp
     */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * Returns the last successful assertion time.
     *
     * @return last-use timestamp, or null before first use
     */
    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    /**
     * Disables the credential without deleting its security history.
     */
    public void disable() {
        this.enabled = false;
    }
}
