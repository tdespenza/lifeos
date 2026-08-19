package com.lifeos.profile.service;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import org.springframework.stereotype.Component;

/** Bounded authorization decision metrics; tags intentionally never contain account or resource IDs. */
@Component
public class ProfileAuthorizationMetrics {

    private final MeterRegistry meterRegistry;

    public ProfileAuthorizationMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void record(String action, String outcome) {
        meterRegistry.counter(
                        "lifeos.profile.authorization.decisions",
                        "action",
                        boundedAction(action),
                        "outcome",
                        outcome)
                .increment();
    }

    private static String boundedAction(String action) {
        return action.replace(':', '_').replace('-', '_').toLowerCase(Locale.ROOT);
    }
}
