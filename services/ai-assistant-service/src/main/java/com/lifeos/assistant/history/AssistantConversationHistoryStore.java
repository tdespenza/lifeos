package com.lifeos.assistant.history;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Explicit conversation-content persistence boundary; disabled deployments use a no-op. */
public interface AssistantConversationHistoryStore {

    void append(UUID ownerAccountId, UUID conversationId, String role, String content);

    List<HistoryEntry> read(UUID ownerAccountId, UUID conversationId);

    record HistoryEntry(String role, String content, Instant createdAt) {
    }
}
