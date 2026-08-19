package com.lifeos.calendar.reminder;

import com.lifeos.calendar.api.CalendarRecurrence;
import com.lifeos.calendar.api.CalendarRecurrenceFrequency;
import com.lifeos.calendar.api.CalendarRecurrenceRequest;
import com.lifeos.calendar.domain.CalendarEvent;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Expands the first bounded recurrence subset in local civil time. Its runtime is O(k), where k
 * is capped by configured occurrence count rather than the age of a recurrence series.
 */
@Component
public class CalendarRecurrenceExpander {

    /** Expands only future intervals after the already-persisted base occurrence. */
    public List<CalendarOccurrenceWindow> expand(
            CalendarEvent event, Instant windowStart, Instant horizon, int maximumOccurrences) {
        if (event.getRecurrenceRule() == null) {
            return List.of();
        }
        if (windowStart == null || horizon == null || horizon.isBefore(windowStart) || maximumOccurrences < 1) {
            throw new IllegalArgumentException("recurrence window and maximum occurrences must be valid");
        }
        CalendarRecurrenceRequest recurrence = CalendarRecurrence.fromStored(event.getRecurrenceRule());
        Duration duration = Duration.between(event.getStartAt(), event.getEndAt());
        ZoneId zone = ZoneId.of(event.getTimeZone());
        ZonedDateTime base = event.getStartAt().atZone(zone);
        List<CalendarOccurrenceWindow> values = new ArrayList<>();
        int count = recurrence.count();
        for (int ordinal = 1; ordinal < count && values.size() < maximumOccurrences; ordinal++) {
            ZonedDateTime localStart = advance(base, recurrence.frequency(), recurrence.interval(), ordinal);
            Instant startAt = localStart.toInstant();
            if (startAt.isAfter(horizon)) {
                break;
            }
            if (!startAt.isBefore(windowStart)) {
                values.add(new CalendarOccurrenceWindow(startAt, startAt.plus(duration)));
            }
        }
        return List.copyOf(values);
    }

    private static ZonedDateTime advance(
            ZonedDateTime base, CalendarRecurrenceFrequency frequency, int interval, int ordinal) {
        long amount = Math.multiplyExact((long) interval, ordinal);
        return switch (frequency) {
            case DAILY -> base.plusDays(amount);
            case WEEKLY -> base.plusWeeks(amount);
            case MONTHLY -> base.plusMonths(amount);
        };
    }
}
