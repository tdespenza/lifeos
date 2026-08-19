package com.lifeos.calendar.domain;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Durable producer dead-letter repository. */
public interface CalendarOutboxDeadLetterRepository extends JpaRepository<CalendarOutboxDeadLetter, UUID> {
}
