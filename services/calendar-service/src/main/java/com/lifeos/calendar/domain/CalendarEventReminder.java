package com.lifeos.calendar.domain;

import com.lifeos.events.v1.NotificationChannel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Durable event-level reminder template used whenever a recurring occurrence is materialized. */
@Entity
@Table(name = "calendar_event_reminder")
public class CalendarEventReminder {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "minutes_before", nullable = false, updatable = false)
    private int minutesBefore;

    @Column(name = "requested_channels", nullable = false, length = 128, updatable = false)
    private String requestedChannels;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected CalendarEventReminder() {
    }

    private CalendarEventReminder(UUID eventId, int minutesBefore, Set<NotificationChannel> channels, Instant now) {
        id = UUID.randomUUID();
        this.eventId = Objects.requireNonNull(eventId, "eventId must not be null");
        if (minutesBefore < 0 || minutesBefore > 10_080) {
            throw new IllegalArgumentException("minutesBefore must be between zero and seven days");
        }
        this.minutesBefore = minutesBefore;
        requestedChannels = encodeChannels(channels);
        createdAt = Objects.requireNonNull(now, "now must not be null");
    }

    public static CalendarEventReminder of(UUID eventId, int minutesBefore, Set<NotificationChannel> channels, Instant now) {
        return new CalendarEventReminder(eventId, minutesBefore, channels, now);
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

    public int getMinutesBefore() {
        return minutesBefore;
    }

    public Set<NotificationChannel> channels() {
        return Arrays.stream(requestedChannels.split(","))
                .map(NotificationChannel::valueOf)
                .collect(Collectors.toUnmodifiableSet());
    }
}
