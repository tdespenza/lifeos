package com.lifeos.assistant.conversation;

import java.math.BigDecimal;
import java.util.UUID;

public record AssistantConversationSummaryResult(
        UUID conversationId,
        int sourceMessageCount,
        String content,
        String providerId,
        String modelName,
        BigDecimal confidenceScore) {
}
