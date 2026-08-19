package com.lifeos.identity.notification;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Low-cardinality recovery notification relay metrics. */
@Component
public class IdentityNotificationOutboxMetrics {

    private final MeterRegistry meterRegistry;

    public IdentityNotificationOutboxMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void recordPublished() {
        meterRegistry.counter("identity.recovery.notification.outbox", "outcome", "published").increment();
    }

    public void recordRetry() {
        meterRegistry.counter("identity.recovery.notification.outbox", "outcome", "retry").increment();
    }

    public void recordDeadLetter() {
        meterRegistry.counter("identity.recovery.notification.outbox", "outcome", "dead_letter").increment();
    }
}
