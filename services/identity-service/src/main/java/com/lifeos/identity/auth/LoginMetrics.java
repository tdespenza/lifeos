package com.lifeos.identity.auth;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Low-cardinality metrics for first-party authentication outcomes.
 */
@Component
public class LoginMetrics {

    private final MeterRegistry meterRegistry;

    /**
     * Creates the login metrics recorder.
     *
     * @param meterRegistry application meter registry
     */
    public LoginMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * Increments a counter labelled only by the bounded outcome enum.
     *
     * @param eventType authentication outcome
     */
    public void record(SecurityAuditEventType eventType) {
        Counter.builder("identity_login_outcomes_total")
                .description("First-party login outcomes")
                .tag("outcome", eventType.name().toLowerCase(Locale.ROOT))
                .register(meterRegistry)
                .increment();
    }
}
