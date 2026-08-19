package com.lifeos.assistant.provider;

import com.lifeos.assistant.conversation.AssistantConversationPurpose;

/** Bounded redacted request passed to a provider implementation, never persisted by this module. */
public record AssistantProviderRequest(
        String promptTemplateId,
        AssistantConversationPurpose purpose,
        String redactedPrompt,
        int maxOutputTokens) {

    @Override
    public String toString() {
        return "AssistantProviderRequest[redacted]";
    }
}
