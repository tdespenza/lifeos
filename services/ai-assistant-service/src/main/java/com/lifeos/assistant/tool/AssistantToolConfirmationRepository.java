package com.lifeos.assistant.tool;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface AssistantToolConfirmationRepository extends JpaRepository<AssistantToolConfirmation, UUID> {

    Optional<AssistantToolConfirmation> findByConversationIdAndOwnerAccountIdAndIdempotencyKeyHash(
            UUID conversationId, UUID ownerAccountId, String idempotencyKeyHash);
}
