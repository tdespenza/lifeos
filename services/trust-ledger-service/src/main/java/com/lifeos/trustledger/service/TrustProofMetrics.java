package com.lifeos.trustledger.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Low-cardinality latency/outcome measurements for the Trust Ledger cryptographic hot paths. */
@Component
public class TrustProofMetrics {

    private final MeterRegistry registry;

    public TrustProofMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
    }

    public Timer.Sample start() {
        return Timer.start(registry);
    }

    public void stop(Timer.Sample sample, String operation, String outcome) {
        sample.stop(Timer.builder("lifeos.trust.proof.operation")
                .description("Bounded Trust Ledger proof operation latency")
                .tag("operation", operation)
                .tag("outcome", outcome)
                .register(registry));
    }
}
