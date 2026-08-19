package com.lifeos.notification.persistence;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Pessimistically locks a recipient cursor while a notification sequence is allocated. */
public interface NotificationSubjectSequenceRepository extends JpaRepository<NotificationSubjectSequence, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select cursor from NotificationSubjectSequence cursor where cursor.recipientAccountId = :accountId")
    Optional<NotificationSubjectSequence> findByRecipientAccountIdForUpdate(@Param("accountId") UUID accountId);
}
