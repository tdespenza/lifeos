package com.lifeos.calendar.reminder;

import static org.assertj.core.api.Assertions.assertThat;

import com.lifeos.calendar.api.CalendarRecurrence;
import com.lifeos.calendar.api.CalendarRecurrenceFrequency;
import com.lifeos.calendar.api.CalendarRecurrenceRequest;
import com.lifeos.calendar.domain.CalendarEvent;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CalendarRecurrenceExpanderTest {

    private final CalendarRecurrenceExpander expander = new CalendarRecurrenceExpander();

    @Test
    void materializesBoundedDailyOccurrencesInTheEventTimeZone() {
        CalendarEvent event = CalendarEvent.active(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID().toString(),
                "Recurring focus",
                null,
                Instant.parse("2026-03-07T15:00:00Z"),
                Instant.parse("2026-03-07T16:00:00Z"),
                "America/Chicago",
                CalendarRecurrence.toStored(new CalendarRecurrenceRequest(CalendarRecurrenceFrequency.DAILY, 1, 3)),
                UUID.randomUUID(),
                Instant.parse("2026-03-01T00:00:00Z"));

        assertThat(expander.expand(
                        event,
                        Instant.parse("2026-03-07T00:00:00Z"),
                        Instant.parse("2026-03-12T00:00:00Z"),
                        100))
                .extracting(CalendarOccurrenceWindow::startAt)
                .containsExactly(Instant.parse("2026-03-08T14:00:00Z"), Instant.parse("2026-03-09T14:00:00Z"));
    }

    @Test
    void neverExpandsBeyondTheConfiguredWorkBound() {
        CalendarEvent event = CalendarEvent.active(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID().toString(),
                "Bounded recurrence",
                null,
                Instant.parse("2026-08-01T12:00:00Z"),
                Instant.parse("2026-08-01T13:00:00Z"),
                "UTC",
                CalendarRecurrence.toStored(new CalendarRecurrenceRequest(CalendarRecurrenceFrequency.DAILY, 1, 1000)),
                UUID.randomUUID(),
                Instant.parse("2026-08-01T00:00:00Z"));

        assertThat(expander.expand(
                        event,
                        Instant.parse("2026-08-01T00:00:00Z"),
                        Instant.parse("2027-08-01T00:00:00Z"),
                        4))
                .hasSize(4);
    }

    @Test
    void skipsHistoricSeriesInstancesInsteadOfSpendingTheBoundOnPastOccurrences() {
        CalendarEvent event = CalendarEvent.active(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID().toString(),
                "Long-lived recurrence",
                null,
                Instant.parse("2026-01-01T09:00:00Z"),
                Instant.parse("2026-01-01T10:00:00Z"),
                "UTC",
                CalendarRecurrence.toStored(new CalendarRecurrenceRequest(CalendarRecurrenceFrequency.DAILY, 1, 365)),
                UUID.randomUUID(),
                Instant.parse("2026-01-01T00:00:00Z"));

        assertThat(expander.expand(
                        event,
                        Instant.parse("2026-06-01T00:00:00Z"),
                        Instant.parse("2026-06-03T23:59:59Z"),
                        10))
                .extracting(CalendarOccurrenceWindow::startAt)
                .containsExactly(
                        Instant.parse("2026-06-01T09:00:00Z"),
                        Instant.parse("2026-06-02T09:00:00Z"),
                        Instant.parse("2026-06-03T09:00:00Z"));
    }
}
