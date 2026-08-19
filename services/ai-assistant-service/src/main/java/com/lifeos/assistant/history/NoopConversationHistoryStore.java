package com.lifeos.assistant.history;

import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Default fail-closed no-op; raw prompts/outputs are not retained unless explicitly enabled. */
@Component
@ConditionalOnProperty(value = "ai-assistant.conversation-history.enabled", havingValue = "false", matchIfMissing = true)
public class NoopConversationHistoryStore implements AssistantConversationHistoryStore {

    @Override
    public void append(UUID ownerAccountId, UUID conversationId, String role, String content) {
        // Deliberately discard content when retention is disabled.
    }

    @Override
    public List<HistoryEntry> read(UUID ownerAccountId, UUID conversationId) {
        throw new ConversationHistoryUnavailableException(new IllegalStateException("history is disabled"));
    }
}
