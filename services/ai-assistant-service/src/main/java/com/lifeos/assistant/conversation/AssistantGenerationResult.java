package com.lifeos.assistant.conversation;

import com.lifeos.assistant.tool.AssistantToolPlan;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** One immediate generation result. Content is never retained by this service after the response. */
public record AssistantGenerationResult(
        UUID conversationId,
        AssistantConversationPurpose purpose,
        String content,
        int estimatedInputTokens,
        int maxOutputTokens,
        List<String> safetyFlags,
        AssistantToolPlan toolPlan,
        String providerId,
        String modelName,
        BigDecimal confidenceScore) {

    public AssistantGenerationResult {
        safetyFlags = List.copyOf(safetyFlags);
    }

    @Override
    public String toString() {
        return "AssistantGenerationResult[conversationId=" + conversationId + ", purpose=" + purpose
                + ", content=[redacted]]";
    }
}
