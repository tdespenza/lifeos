package com.lifeos.notification.persistence;

import com.lifeos.events.v1.NotificationChannel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Encrypted, user-owned email or push destination; raw destinations never leave this aggregate. */
@Entity
@Table(name = "notification_endpoint")
public class NotificationEndpoint {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "owner_account_id", nullable = false, updatable = false)
    private UUID ownerAccountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 16, updatable = false)
    private NotificationChannel channel;

    @Column(name = "destination_ciphertext", nullable = false, length = 8192)
    private String destinationCiphertext;

    @Column(name = "destination_hash", nullable = false, length = 64, updatable = false)
    private String destinationHash;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "disabled_reason", length = 80)
    private String disabledReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected NotificationEndpoint() {
    }

    private NotificationEndpoint(
            UUID id,
            UUID ownerAccountId,
            NotificationChannel channel,
            String destinationCiphertext,
            String destinationHash,
            Instant now) {
        if (channel == NotificationChannel.REALTIME) {
            throw new IllegalArgumentException("realtime streams do not have stored destinations");
        }
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.ownerAccountId = Objects.requireNonNull(ownerAccountId, "ownerAccountId must not be null");
        this.channel = Objects.requireNonNull(channel, "channel must not be null");
        this.destinationCiphertext = requireText(destinationCiphertext, "destinationCiphertext");
        this.destinationHash = requireDigest(destinationHash);
        this.enabled = true;
        this.createdAt = Objects.requireNonNull(now, "now must not be null");
        this.updatedAt = now;
    }

    public static NotificationEndpoint enabled(
            UUID id,
            UUID ownerAccountId,
            NotificationChannel channel,
            String destinationCiphertext,
            String destinationHash,
            Instant now) {
        return new NotificationEndpoint(id, ownerAccountId, channel, destinationCiphertext, destinationHash, now);
    }

    public void disable(String reason, Instant now) {
        this.enabled = false;
        this.disabledReason = requireReason(reason);
        this.updatedAt = Objects.requireNonNull(now, "now must not be null");
    }

    public UUID getId() {
        return id;
    }

    public UUID getOwnerAccountId() {
        return ownerAccountId;
    }

    public NotificationChannel getChannel() {
        return channel;
    }

    public String getDestinationCiphertext() {
        return destinationCiphertext;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getDisabledReason() {
        return disabledReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static String requireDigest(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("destinationHash must be a SHA-256 hex digest");
        }
        return value;
    }

    private static String requireReason(String value) {
        if (value == null || !value.matches("[A-Z0-9_]{1,80}")) {
            throw new IllegalArgumentException("disabled reason must be a bounded reason code");
        }
        return value;
    }
}
