package com.lifeos.calendar.outbox;

import com.lifeos.calendar.config.CalendarProperties;
import com.lifeos.calendar.domain.CalendarOutboxDeadLetter;
import com.lifeos.calendar.domain.CalendarOutboxDeadLetterRepository;
import com.lifeos.calendar.domain.CalendarOutboxEvent;
import com.lifeos.calendar.domain.CalendarOutboxEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Short transactional claim/finalize operations on Calendar's local producer outbox. */
@Service
public class CalendarOutboxTransactions {

    private final CalendarOutboxEventRepository outboxRepository;
    private final CalendarOutboxDeadLetterRepository deadLetterRepository;
    private final CalendarProperties properties;
    private final FullJitterRetryPolicy retryPolicy;
    private final Clock clock;

    public CalendarOutboxTransactions(
            CalendarOutboxEventRepository outboxRepository,
            CalendarOutboxDeadLetterRepository deadLetterRepository,
            CalendarProperties properties,
            @Qualifier("calendarOutboxRetryPolicy") FullJitterRetryPolicy retryPolicy,
            Clock clock) {
        this.outboxRepository = outboxRepository;
        this.deadLetterRepository = deadLetterRepository;
        this.properties = properties;
        this.retryPolicy = retryPolicy;
        this.clock = clock;
    }

    @Transactional
    public List<ClaimedCalendarOutboxEvent> claimBatch() {
        Instant now = clock.instant();
        int claimLimit = Math.min(
                properties.getOutbox().getBatchSize(), properties.getOutbox().getMaxConcurrentPublishes());
        return outboxRepository.findClaimableForUpdate(now, claimLimit).stream()
                .map(event -> {
                    var lease = event.claim(now, properties.getOutbox().getLeaseDuration());
                    return new ClaimedCalendarOutboxEvent(
                            event.getId(),
                            lease,
                            event.getTopic(),
                            event.getPartitionKey(),
                            event.getPayloadJson(),
                            event.getHeadersJson(),
                            event.getAttemptCount());
                })
                .toList();
    }

    @Transactional
    public boolean markPublished(ClaimedCalendarOutboxEvent claimed) {
        CalendarOutboxEvent event = outboxRepository.findById(claimed.id()).orElse(null);
        if (event == null || !claimed.leaseToken().equals(event.getLeaseToken())) {
            return false;
        }
        event.markPublished(claimed.leaseToken(), clock.instant());
        return true;
    }

    @Transactional
    public boolean rescheduleOrDeadLetter(ClaimedCalendarOutboxEvent claimed) {
        CalendarOutboxEvent event = outboxRepository.findById(claimed.id()).orElse(null);
        if (event == null || !claimed.leaseToken().equals(event.getLeaseToken())) {
            return false;
        }
        if (event.getAttemptCount() >= properties.getOutbox().getMaxAttempts()) {
            event.deadLetter(claimed.leaseToken(), "KAFKA_PUBLISH_FAILURE");
            deadLetterRepository.save(CalendarOutboxDeadLetter.from(event, "KAFKA_PUBLISH_FAILURE", clock.instant()));
            return false;
        }
        event.reschedule(
                claimed.leaseToken(),
                clock.instant().plus(retryPolicy.nextDelay(event.getAttemptCount())),
                "KAFKA_PUBLISH_FAILURE");
        return true;
    }
}
