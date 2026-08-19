package com.lifeos.trustledger.anchor;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.Test;

class TrustAnchorMetricsTest {

    @Test
    void recordsOnlyBoundedOperationAndOutcomeTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TrustAnchorMetrics metrics = new TrustAnchorMetrics(registry);

        Timer.Sample sample = metrics.start();
        metrics.stop(sample, "digest", "confirmed");

        Timer timer = registry.find("lifeos.trust.anchor.operation")
                .tag("operation", "digest")
                .tag("outcome", "confirmed")
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }
}
