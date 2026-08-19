package com.lifeos.documentvault.proof;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentProofOutboxDeadLetterRepository
        extends JpaRepository<DocumentProofOutboxDeadLetter, UUID> {
}
