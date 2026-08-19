package com.lifeos.assistant.conversation;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repository exposes owner-constrained lookup only, preventing conversation enumeration. */
public interface AssistantConversationRepository extends JpaRepository<AssistantConversation, UUID> {

    Optional<AssistantConversation> findByIdAndOwnerAccountId(UUID id, UUID ownerAccountId);
}
