package com.lifeos.notification.delivery;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Low-cardinality operational metrics; account, destination, and event IDs are never tags. */
@Component
public class NotificationMetrics {

    private final MeterRegistry registry;

    public NotificationMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordDelivery(DeliveryCompletion completion) {
        Counter.builder("notification.delivery.outcomes")
                .tag("channel", completion.channel().name().toLowerCase())
                .tag("outcome", completion.outcome().name().toLowerCase())
                .tag("reason", completion.reasonCode())
                .register(registry)
                .increment();
    }

    public void recordOutbox(boolean published) {
        Counter.builder("notification.outbox.relay.outcomes")
                .tag("outcome", published ? "published" : "retry")
                .register(registry)
                .increment();
    }
}
