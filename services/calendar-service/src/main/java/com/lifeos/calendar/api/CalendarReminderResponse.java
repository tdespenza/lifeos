package com.lifeos.calendar.api;

import com.lifeos.events.v1.NotificationChannel;
import java.util.Set;

/** Persisted event-level reminder template representation. */
public record CalendarReminderResponse(int minutesBefore, Set<NotificationChannel> requestedChannels) {
}
