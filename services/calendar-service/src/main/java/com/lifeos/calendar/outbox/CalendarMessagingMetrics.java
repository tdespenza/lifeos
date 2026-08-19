package com.lifeos.calendar.outbox;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Low-cardinality messaging signals for scheduler lag, relay outcome, and operator intervention. */
@Component
public class CalendarMessagingMetrics {

    private final MeterRegistry meterRegistry;

    public CalendarMessagingMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void recordReminderClaim(int count) {
        meterRegistry.counter("calendar.reminder.claimed").increment(count);
    }

    public void recordOutbox(boolean success) {
        meterRegistry.counter("calendar.outbox.publish", "outcome", success ? "published" : "retry").increment();
    }

    public void recordDeadLetter() {
        meterRegistry.counter("calendar.outbox.dead_letter").increment();
    }
}
