package com.lifeos.gateway.auth;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Redacted, bounded authentication metrics for gateway security operations.
 */
@Component
public class GatewayAuthenticationMetrics {

    private final MeterRegistry meterRegistry;

    /**
     * Creates the gateway authentication metric publisher.
     *
     * @param meterRegistry application meter registry
     */
    public GatewayAuthenticationMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * Records one rejected protected request without token, account, session, or client labels.
     *
     * @param routeId finite configured route identifier
     * @param reason bounded rejection category
     */
    public void recordRejection(String routeId, String reason) {
        Counter.builder("gateway.authentication.rejections")
                .description("Rejected protected requests at the gateway authentication boundary")
                .tag("route", Objects.requireNonNullElse(routeId, "unknown"))
                .tag("reason", Objects.requireNonNullElse(reason, "unknown"))
                .register(meterRegistry)
                .increment();
    }
}
