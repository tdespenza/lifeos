package com.lifeos.assistant.provider;

import java.math.BigDecimal;
/** Structured response from a future provider adapter; its text is returned once and not stored. */
public record AssistantProviderResponse(String text, String providerId, String modelName, BigDecimal confidenceScore) {

    @Override
    public String toString() {
        return "AssistantProviderResponse[redacted]";
    }
}
