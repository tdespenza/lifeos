package com.lifeos.media.service;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Low-cardinality outcome counters for the media control plane. */
@Component
public class MediaMetrics {

    private final MeterRegistry registry;

    public MediaMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void record(String operation, String outcome) {
        registry.counter("lifeos.media.operation", "operation", operation, "outcome", outcome).increment();
    }
}
