package com.lifeos.documentvault.idempotency;

import com.lifeos.documentvault.service.DocumentView;
import java.util.UUID;

/** Safe projection of a durable reservation used to coordinate object promotion outside the DB. */
public record DocumentCommandReservation(UUID id, UUID documentId, DocumentView completedResult) {

    public boolean isCompleted() {
        return completedResult != null;
    }
}
