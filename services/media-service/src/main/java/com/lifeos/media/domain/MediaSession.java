package com.lifeos.media.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;

/** Versioned owner-only scheduling record for a future external SFU room. */
@Entity
@Table(
        name = "media_session",
        indexes = @Index(name = "idx_media_session_owner_start", columnList = "tenant_id, owner_account_id, scheduled_start_at, id"))
public class MediaSession {

    private static final Duration EARLY_JOIN_WINDOW = Duration.ofMinutes(10);

    @Id
    private UUID id;

    @Column(name = "owner_account_id", nullable = false, updatable = false)
    private UUID ownerAccountId;

    @Column(name = "tenant_id", nullable = false, length = 255, updatable = false)
    private String tenantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private MediaSessionKind kind;

    @Column(nullable = false, length = 140)
    private String title;

    @Column(name = "scheduled_start_at", nullable = false)
    private Instant scheduledStartAt;

    @Column(name = "scheduled_end_at", nullable = false)
    private Instant scheduledEndAt;

    @Column(name = "time_zone", nullable = false, length = 64)
    private String timeZone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private MediaSessionStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected MediaSession() {
    }

    private MediaSession(
            UUID id,
            UUID ownerAccountId,
            String tenantId,
            MediaSessionKind kind,
            String title,
            Instant scheduledStartAt,
            Instant scheduledEndAt,
            String timeZone,
            Instant now) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.ownerAccountId = Objects.requireNonNull(ownerAccountId, "ownerAccountId must not be null");
        this.tenantId = requireText(tenantId, "tenantId", 255);
        assign(kind, title, scheduledStartAt, scheduledEndAt, timeZone);
        status = MediaSessionStatus.SCHEDULED;
        createdAt = Objects.requireNonNull(now, "now must not be null");
        updatedAt = now;
    }

    public static MediaSession scheduled(
            UUID id,
            UUID ownerAccountId,
            String tenantId,
            MediaSessionKind kind,
            String title,
            Instant scheduledStartAt,
            Instant scheduledEndAt,
            String timeZone,
            Instant now) {
        return new MediaSession(
                id, ownerAccountId, tenantId, kind, title, scheduledStartAt, scheduledEndAt, timeZone, now);
    }

    public void reschedule(
            MediaSessionKind valueKind,
            String valueTitle,
            Instant valueStart,
            Instant valueEnd,
            String valueTimeZone,
            Instant now) {
        requireScheduled("update");
        assign(valueKind, valueTitle, valueStart, valueEnd, valueTimeZone);
        updatedAt = Objects.requireNonNull(now, "now must not be null");
    }

    public void cancel(Instant now) {
        requireScheduled("cancel");
        status = MediaSessionStatus.CANCELLED;
        updatedAt = Objects.requireNonNull(now, "now must not be null");
    }

    /**
     * Durably closes a scheduled session whose authoritative end instant has passed.
     *
     * @param now current wall-clock instant
     * @return whether this invocation changed the durable status
     */
    public boolean endIfDue(Instant now) {
        Instant valueNow = Objects.requireNonNull(now, "now must not be null");
        if (status != MediaSessionStatus.SCHEDULED || valueNow.isBefore(scheduledEndAt)) {
            return false;
        }
        status = MediaSessionStatus.ENDED;
        updatedAt = valueNow;
        return true;
    }

    /** Admission is bounded to the authoritative scheduled window. */
    public boolean isJoinableAt(Instant now) {
        return status == MediaSessionStatus.SCHEDULED
                && !now.isBefore(scheduledStartAt.minus(EARLY_JOIN_WINDOW))
                && now.isBefore(scheduledEndAt);
    }

    public MediaSessionStatus effectiveStatus(Instant now) {
        if (status == MediaSessionStatus.SCHEDULED && !now.isBefore(scheduledEndAt)) {
            return MediaSessionStatus.ENDED;
        }
        return status;
    }

    public String roomId() {
        return "media-room-" + id;
    }

    private void requireScheduled(String operation) {
        if (status != MediaSessionStatus.SCHEDULED) {
            throw new MediaLifecycleTransitionException(operation);
        }
    }

    private void assign(
            MediaSessionKind valueKind,
            String valueTitle,
            Instant valueStart,
            Instant valueEnd,
            String valueTimeZone) {
        kind = Objects.requireNonNull(valueKind, "kind must not be null");
        title = requireText(valueTitle, "title", 140);
        scheduledStartAt = Objects.requireNonNull(valueStart, "scheduledStartAt must not be null");
        scheduledEndAt = Objects.requireNonNull(valueEnd, "scheduledEndAt must not be null");
        if (!scheduledEndAt.isAfter(scheduledStartAt)) {
            throw new IllegalArgumentException("scheduled end must be after scheduled start");
        }
        timeZone = requireTimeZone(valueTimeZone);
    }

    private static String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(field + " must be nonblank and within its storage bound");
        }
        return value;
    }

    private static String requireTimeZone(String value) {
        if (value == null || value.isBlank() || value.length() > 64) {
            throw new IllegalArgumentException("timeZone must be a bounded IANA zone identifier");
        }
        try {
            return ZoneId.of(value).getId();
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("timeZone must be a valid IANA zone identifier", exception);
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getOwnerAccountId() {
        return ownerAccountId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public MediaSessionKind getKind() {
        return kind;
    }

    public String getTitle() {
        return title;
    }

    public Instant getScheduledStartAt() {
        return scheduledStartAt;
    }

    public Instant getScheduledEndAt() {
        return scheduledEndAt;
    }

    public String getTimeZone() {
        return timeZone;
    }

    public MediaSessionStatus getStatus() {
        return status;
    }

    public long getVersion() {
        return version;
    }
}
