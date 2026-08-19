package com.lifeos.calendar.api;

import com.lifeos.events.v1.NotificationChannel;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Set;

/** Explicit event-owned reminder configuration; no global notification preference is inferred. */
public record CalendarReminderRequest(
        @Min(0) @Max(10_080) int minutesBefore,
        @NotEmpty @Size(max = 3) Set<@NotNull NotificationChannel> requestedChannels) {
}
