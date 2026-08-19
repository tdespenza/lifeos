package com.lifeos.notification.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Recipient-scoped reads for bounded list and stream replay operations. */
public interface NotificationRecordRepository extends JpaRepository<NotificationRecord, UUID> {

    List<NotificationRecord> findByRecipientAccountIdAndSequenceNumberGreaterThanOrderBySequenceNumberAsc(
            UUID recipientAccountId, long sequenceNumber, Pageable pageable);

    List<NotificationRecord> findByRecipientAccountIdOrderBySequenceNumberAsc(UUID recipientAccountId, Pageable pageable);
}
