package com.lifeos.documentvault.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Low-cardinality operation/outcome counters; identifiers and titles are intentionally not tags. */
@Component
public class DocumentVaultMetrics {

    private final MeterRegistry meterRegistry;

    public DocumentVaultMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void record(String operation, String outcome) {
        Counter.builder("lifeos.document_vault.operations")
                .description("Document Vault owner-scoped operation outcomes")
                .tag("operation", operation)
                .tag("outcome", outcome)
                .register(meterRegistry)
                .increment();
    }
}
