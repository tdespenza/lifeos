package com.lifeos.identity.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Durable reservation for one public account-registration command.
 *
 * <p>No raw idempotency key, email, display name, or password is retained. Both stored digests
 * are HMAC-SHA-256 values produced by {@code RegistrationIdempotencyFingerprint}.
 */
@Entity
@Table(
        name = "account_registration_idempotency",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_account_registration_idempotency_key",
                columnNames = "idempotency_key_hash"),
        indexes = @Index(name = "idx_account_registration_idempotency_account", columnList = "account_id"))
public class AccountRegistrationIdempotency {

    @Id
    private UUID id;

    @Column(name = "idempotency_key_hash", nullable = false, length = 64, updatable = false)
    private String idempotencyKeyHash;

    @Column(name = "request_fingerprint", nullable = false, length = 64, updatable = false)
    private String requestFingerprint;

    @Column(name = "account_id")
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AccountRegistrationIdempotencyState state;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected AccountRegistrationIdempotency() {
        // required by JPA
    }

    /**
     * Creates a durable pending reservation.
     *
     * @param idempotencyKeyHash HMAC digest of the opaque client key
     * @param requestFingerprint HMAC digest of the canonical request
     */
    public AccountRegistrationIdempotency(String idempotencyKeyHash, String requestFingerprint) {
        this.id = UUID.randomUUID();
        this.idempotencyKeyHash = requireDigest(idempotencyKeyHash, "idempotencyKeyHash");
        this.requestFingerprint = requireDigest(requestFingerprint, "requestFingerprint");
        this.state = AccountRegistrationIdempotencyState.PENDING;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public boolean isCompleted() {
        return state == AccountRegistrationIdempotencyState.COMPLETED;
    }

    /**
     * Returns whether a retry has the exact canonical payload of the original request.
     *
     * @param candidateFingerprint HMAC digest derived from the retry payload
     * @return whether the stored payload matches
     */
    public boolean matchesRequestFingerprint(String candidateFingerprint) {
        return requestFingerprint.equals(candidateFingerprint);
    }

    /** Marks this reservation complete after its account and credential have been persisted. */
    public void complete(UUID accountId) {
        if (state == AccountRegistrationIdempotencyState.PENDING) {
            this.accountId = Objects.requireNonNull(accountId, "accountId must not be null");
            this.state = AccountRegistrationIdempotencyState.COMPLETED;
            this.completedAt = Instant.now();
        }
    }

    private static String requireDigest(String value, String name) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a SHA-256-sized digest");
        }
        return value;
    }
}
