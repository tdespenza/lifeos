package com.lifeos.identity.authorization;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import org.springframework.stereotype.Component;

/** Low-cardinality observability for authorization decisions. */
@Component
public class AuthorizationMetrics {

    private final MeterRegistry meterRegistry;

    /**
     * Creates the metrics recorder.
     *
     * @param meterRegistry application metric registry
     */
    public AuthorizationMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * Increments a metric with bounded outcome and reason dimensions only.
     *
     * @param decision completed authorization decision
     */
    public void record(AuthorizationDecision decision) {
        Counter.builder("identity_authorization_decisions_total")
                .description("Identity authorization decisions")
                .tag("outcome", decision.outcome().name().toLowerCase(Locale.ROOT))
                .tag("reason", decision.reasonCode().toLowerCase(Locale.ROOT))
                .register(meterRegistry)
                .increment();
    }
}
