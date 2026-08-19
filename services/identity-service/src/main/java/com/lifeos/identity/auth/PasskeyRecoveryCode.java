package com.lifeos.identity.auth;

import com.lifeos.identity.account.UserAccount;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** One hashed, single-use passkey recovery code. The raw code never crosses persistence. */
@Entity
@Table(name = "passkey_recovery_code", indexes = {
    @Index(name = "idx_passkey_recovery_account", columnList = "account_id, expires_at")
})
public class PasskeyRecoveryCode {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private UserAccount account;

    @Column(name = "code_hash", nullable = false, length = 64)
    private String codeHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected PasskeyRecoveryCode() {
    }

    public PasskeyRecoveryCode(UserAccount account, String codeHash, Instant createdAt, Instant expiresAt) {
        this.account = Objects.requireNonNull(account, "account must not be null");
        if (codeHash == null || !codeHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("codeHash must be a SHA-256-sized digest");
        }
        this.codeHash = codeHash;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("expiresAt must be after createdAt");
        }
    }

    public boolean isUsable(Instant now) {
        return usedAt == null && now != null && now.isBefore(expiresAt);
    }

    public void consume(Instant now) {
        if (!isUsable(now)) {
            throw new IllegalStateException("recovery code is not usable");
        }
        usedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UserAccount getAccount() {
        return account;
    }

    public String getCodeHash() {
        return codeHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getUsedAt() {
        return usedAt;
    }

    public long getVersion() {
        return version;
    }
}
