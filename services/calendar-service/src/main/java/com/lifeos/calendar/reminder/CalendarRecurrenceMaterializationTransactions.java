package com.lifeos.calendar.reminder;

import com.lifeos.calendar.config.CalendarProperties;
import com.lifeos.calendar.domain.CalendarEvent;
import com.lifeos.calendar.domain.CalendarEventRepository;
import com.lifeos.calendar.domain.CalendarEventStatus;
import com.lifeos.calendar.domain.CalendarOccurrenceRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Short per-series transaction that prevents duplicate expansion across scheduler replicas. */
@Service
public class CalendarRecurrenceMaterializationTransactions {

    private final CalendarEventRepository eventRepository;
    private final CalendarOccurrenceRepository occurrenceRepository;
    private final CalendarOccurrenceFactory occurrenceFactory;
    private final CalendarRecurrenceExpander expander;
    private final CalendarProperties properties;
    private final Clock clock;

    public CalendarRecurrenceMaterializationTransactions(
            CalendarEventRepository eventRepository,
            CalendarOccurrenceRepository occurrenceRepository,
            CalendarOccurrenceFactory occurrenceFactory,
            CalendarRecurrenceExpander expander,
            CalendarProperties properties,
            Clock clock) {
        this.eventRepository = eventRepository;
        this.occurrenceRepository = occurrenceRepository;
        this.occurrenceFactory = occurrenceFactory;
        this.expander = expander;
        this.properties = properties;
        this.clock = clock;
    }

    /** Locks a series, then inserts only as-yet-unmaterialized future start instants. */
    @Transactional
    public int materialize(UUID eventId) {
        CalendarEvent event = eventRepository.findByIdForUpdate(eventId).orElse(null);
        if (event == null || event.getStatus() != CalendarEventStatus.ACTIVE || event.getRecurrenceRule() == null) {
            return 0;
        }
        Instant now = clock.instant();
        if (!event.isMaterializationDue(now)) {
            return 0;
        }
        Instant horizon = now.plus(java.time.Duration.ofDays(properties.getRecurrence().getHorizonDays()));
        HashSet<Instant> existingStarts = occurrenceRepository
                .findByEventIdAndRecurrenceRevision(event.getId(), event.getRecurrenceRevision())
                .stream()
                .map(value -> value.getStartAt())
                .collect(java.util.stream.Collectors.toCollection(HashSet::new));
        int created = 0;
        for (CalendarOccurrenceWindow occurrence : expander.expand(
                event, now, horizon, properties.getRecurrence().getMaxOccurrencesPerEvent())) {
            if (existingStarts.add(occurrence.startAt())) {
                occurrenceFactory.create(
                        event, event.getRecurrenceRevision(), occurrence.startAt(), occurrence.endAt(), now);
                created++;
            }
        }
        event.deferRecurrenceMaterialization(now.plus(properties.getRecurrence().getPollDelay()));
        return created;
    }
}
