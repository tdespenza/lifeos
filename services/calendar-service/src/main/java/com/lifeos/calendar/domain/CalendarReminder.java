package com.lifeos.calendar.domain;

import com.lifeos.events.v1.NotificationChannel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** A durable one-per-occurrence reminder with a producer-stable notification event identity. */
@Entity
@Table(name = "calendar_reminder")
public class CalendarReminder {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "occurrence_id", nullable = false, updatable = false)
    private UUID occurrenceId;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "owner_account_id", nullable = false, updatable = false)
    private UUID ownerAccountId;

    @Column(name = "tenant_id", nullable = false, length = 255, updatable = false)
    private String tenantId;

    @Column(name = "minutes_before", nullable = false, updatable = false)
    private int minutesBefore;

    @Column(name = "requested_channels", nullable = false, length = 128, updatable = false)
    private String requestedChannels;

    @Column(name = "due_at", nullable = false, updatable = false)
    private Instant dueAt;

    @Column(name = "event_time_zone", nullable = false, length = 64, updatable = false)
    private String eventTimeZone;

    @Column(name = "notification_event_id", nullable = false, updatable = false)
    private UUID notificationEventId;

    @Column(name = "correlation_id", nullable = false, updatable = false)
    private UUID correlationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CalendarReminderState state;

    @Column(name = "lease_token")
    private UUID leaseToken;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected CalendarReminder() {
    }

    private CalendarReminder(
            UUID id,
            CalendarOccurrence occurrence,
            int minutesBefore,
            Set<NotificationChannel> channels,
            UUID notificationEventId,
            Instant now) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        occurrenceId = occurrence.getId();
        eventId = occurrence.getEventId();
        ownerAccountId = occurrence.getOwnerAccountId();
        tenantId = occurrence.getTenantId();
        if (minutesBefore < 0 || minutesBefore > 10_080) {
            throw new IllegalArgumentException("minutesBefore must be between zero and seven days");
        }
        this.minutesBefore = minutesBefore;
        requestedChannels = encodeChannels(channels);
        dueAt = occurrence.getStartAt().minus(Duration.ofMinutes(minutesBefore));
        eventTimeZone = occurrence.getTimeZone();
        this.notificationEventId = Objects.requireNonNull(notificationEventId, "notificationEventId must not be null");
        correlationId = occurrence.getOriginCorrelationId();
        state = CalendarReminderState.SCHEDULED;
        createdAt = Objects.requireNonNull(now, "now must not be null");
        updatedAt = now;
    }

    public static CalendarReminder scheduled(
            UUID id,
            CalendarOccurrence occurrence,
            int minutesBefore,
            Set<NotificationChannel> channels,
            UUID notificationEventId,
            Instant now) {
        return new CalendarReminder(id, occurrence, minutesBefore, channels, notificationEventId, now);
    }

    /** Claims a due or abandoned reminder while the caller holds a pessimistic database row lock. */
    public UUID claim(Instant now, Duration leaseDuration) {
        boolean due = state == CalendarReminderState.SCHEDULED && !dueAt.isAfter(now);
        boolean abandoned = state == CalendarReminderState.LEASED
                && leaseExpiresAt != null
                && !leaseExpiresAt.isAfter(now);
        if (!due && !abandoned) {
            throw new IllegalStateException("reminder is not claimable");
        }
        leaseToken = UUID.randomUUID();
        leaseExpiresAt = now.plus(requirePositive(leaseDuration));
        state = CalendarReminderState.LEASED;
        updatedAt = now;
        return leaseToken;
    }

    /** Commits an outbox reservation atomically with the lease owner. */
    public void markOutboxed(UUID expectedLease, Instant now) {
        requireLease(expectedLease);
        state = CalendarReminderState.OUTBOXED;
        clearLease();
        updatedAt = Objects.requireNonNull(now, "now must not be null");
    }

    public void cancel(Instant now) {
        if (state == CalendarReminderState.OUTBOXED) {
            return;
        }
        state = CalendarReminderState.CANCELLED;
        clearLease();
        updatedAt = Objects.requireNonNull(now, "now must not be null");
    }

    public void expire(Instant now) {
        if (state == CalendarReminderState.SCHEDULED || state == CalendarReminderState.LEASED) {
            state = CalendarReminderState.EXPIRED;
            clearLease();
            updatedAt = Objects.requireNonNull(now, "now must not be null");
        }
    }

    private void requireLease(UUID expectedLease) {
        if (state != CalendarReminderState.LEASED || leaseToken == null || !leaseToken.equals(expectedLease)) {
            throw new IllegalStateException("reminder lease is no longer held");
        }
    }

    private void clearLease() {
        leaseToken = null;
        leaseExpiresAt = null;
    }

    private static Duration requirePositive(Duration value) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        return value;
    }

    private static String encodeChannels(Set<NotificationChannel> channels) {
        if (channels == null || channels.isEmpty() || channels.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("channels must be nonempty and contain no null");
        }
        String value = channels.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
        if (value.length() > 128) {
            throw new IllegalArgumentException("channels exceed storage bound");
        }
        return value;
    }

    public Set<NotificationChannel> channels() {
        return Arrays.stream(requestedChannels.split(","))
                .map(NotificationChannel::valueOf)
                .collect(Collectors.toUnmodifiableSet());
    }

    public UUID getId() {
        return id;
    }

    public UUID getOccurrenceId() {
        return occurrenceId;
    }

    public UUID getEventId() {
        return eventId;
    }

    public UUID getOwnerAccountId() {
        return ownerAccountId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public int getMinutesBefore() {
        return minutesBefore;
    }

    public Instant getDueAt() {
        return dueAt;
    }

    public String getEventTimeZone() {
        return eventTimeZone;
    }

    public UUID getNotificationEventId() {
        return notificationEventId;
    }

    public UUID getCorrelationId() {
        return correlationId;
    }

    public CalendarReminderState getState() {
        return state;
    }

    public UUID getLeaseToken() {
        return leaseToken;
    }

    public Instant getLeaseExpiresAt() {
        return leaseExpiresAt;
    }
}
