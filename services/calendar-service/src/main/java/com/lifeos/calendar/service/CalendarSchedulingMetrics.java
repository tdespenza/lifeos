package com.lifeos.calendar.service;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Low-cardinality operational signals for intentionally degraded scheduling capabilities. */
@Component
public class CalendarSchedulingMetrics {

    private final MeterRegistry meterRegistry;

    public CalendarSchedulingMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /** Records that bounded local advice ran without a usable TaskGoal planning projection. */
    public void recordOptimizationDegraded() {
        meterRegistry.counter("calendar.optimization.degraded", "source", "task-goal").increment();
    }
}
