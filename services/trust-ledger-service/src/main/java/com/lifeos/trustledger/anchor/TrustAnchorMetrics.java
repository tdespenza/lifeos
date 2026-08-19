package com.lifeos.trustledger.anchor;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Low-cardinality latency/outcome measurements for external anchor workflows. */
@Component
public class TrustAnchorMetrics {

    private final MeterRegistry registry;

    public TrustAnchorMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
    }

    public Timer.Sample start() {
        return Timer.start(registry);
    }

    public void stop(Timer.Sample sample, String operation, String outcome) {
        sample.stop(Timer.builder("lifeos.trust.anchor.operation")
                .description("Bounded Trust Ledger external anchor latency")
                .tag("operation", operation)
                .tag("outcome", outcome)
                .register(registry));
    }
}
