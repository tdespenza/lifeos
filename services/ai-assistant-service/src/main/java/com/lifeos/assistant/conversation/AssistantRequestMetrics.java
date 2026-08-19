package com.lifeos.assistant.conversation;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Low-cardinality operational counters without user, conversation, prompt, or provider text labels. */
@Component
public class AssistantRequestMetrics {

    private final MeterRegistry meterRegistry;

    public AssistantRequestMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void record(AssistantConversationPurpose purpose, String outcome) {
        meterRegistry.counter("lifeos.ai_assistant.requests", "purpose", purpose.name(), "outcome", outcome).increment();
    }
}
