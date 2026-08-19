package com.lifeos.calendar.reminder;

import com.lifeos.calendar.config.CalendarProperties;
import com.lifeos.calendar.domain.CalendarEventRepository;
import com.lifeos.calendar.domain.CalendarEventStatus;
import java.time.Clock;
import org.springframework.data.domain.PageRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Bounded scheduler that creates future recurrence rows without unbounded catch-up. */
@Component
@ConditionalOnProperty(
        value = "calendar.recurrence.materializer-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class CalendarRecurrenceMaterializer {

    private final CalendarEventRepository eventRepository;
    private final CalendarRecurrenceMaterializationTransactions transactions;
    private final CalendarProperties properties;
    private final Clock clock;

    public CalendarRecurrenceMaterializer(
            CalendarEventRepository eventRepository,
            CalendarRecurrenceMaterializationTransactions transactions,
            CalendarProperties properties,
            Clock clock) {
        this.eventRepository = eventRepository;
        this.transactions = transactions;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${calendar.recurrence.poll-delay:1m}")
    public void materializeDueSeries() {
        eventRepository.findMaterializationDue(
                        CalendarEventStatus.ACTIVE,
                        clock.instant(),
                        PageRequest.of(0, properties.getRecurrence().getBatchSize()))
                .stream()
                .forEach(event -> transactions.materialize(event.getId()));
    }
}
