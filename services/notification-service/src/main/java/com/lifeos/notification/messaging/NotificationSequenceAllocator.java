package com.lifeos.notification.messaging;

import com.lifeos.notification.persistence.NotificationSubjectSequence;
import com.lifeos.notification.persistence.NotificationSubjectSequenceRepository;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/** Allocates a recipient-local monotonic stream cursor inside the ingress database transaction. */
@Component
public class NotificationSequenceAllocator {

    private final NotificationSubjectSequenceRepository repository;

    public NotificationSequenceAllocator(NotificationSubjectSequenceRepository repository) {
        this.repository = repository;
    }

    public long next(UUID recipientAccountId) {
        return repository.findByRecipientAccountIdForUpdate(recipientAccountId)
                .map(NotificationSubjectSequence::next)
                .orElseGet(() -> start(recipientAccountId));
    }

    private long start(UUID recipientAccountId) {
        try {
            NotificationSubjectSequence cursor = NotificationSubjectSequence.startingAt(recipientAccountId);
            cursor.next();
            repository.saveAndFlush(cursor);
            return 1;
        } catch (DataIntegrityViolationException exception) {
            // Producers key the topic by recipient, so this can only occur during an unusual
            // cross-partition/manual replay race. Propagating makes the surrounding ingress
            // transaction retry safely instead of assigning duplicate sequence numbers.
            throw exception;
        }
    }
}
