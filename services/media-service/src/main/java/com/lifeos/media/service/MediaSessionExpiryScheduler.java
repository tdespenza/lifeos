package com.lifeos.media.service;

import com.lifeos.media.domain.MediaSession;
import com.lifeos.media.domain.MediaSessionRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Persists scheduled-session expiry independently of reads and join traffic. */
@Component
@ConditionalOnProperty(name = "media.session-expiry.enabled", havingValue = "true", matchIfMissing = true)
public class MediaSessionExpiryScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(MediaSessionExpiryScheduler.class);
    private static final int BATCH_SIZE = 100;

    private final MediaSessionRepository repository;
    private final MediaMetrics metrics;
    private final Clock clock;

    @Autowired
    public MediaSessionExpiryScheduler(MediaSessionRepository repository, MediaMetrics metrics) {
        this(repository, metrics, Clock.systemUTC());
    }

    MediaSessionExpiryScheduler(MediaSessionRepository repository, MediaMetrics metrics, Clock clock) {
        this.repository = repository;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** Runs a bounded durable expiry pass. */
    @Scheduled(fixedDelayString = "${media.session-expiry.poll-delay:1s}")
    @Transactional
    public void expireDueSessions() {
        Instant now = clock.instant();
        try {
            List<MediaSession> due = repository.findDueScheduledForUpdate(now, PageRequest.of(0, BATCH_SIZE));
            int expired = 0;
            for (MediaSession session : due) {
                if (session.endIfDue(now)) {
                    expired++;
                }
            }
            if (expired > 0) {
                repository.flush();
                metrics.record("session-expiry", "ended");
            }
        } catch (DataAccessException exception) {
            // Expiry is retried on the next bounded poll; never stop the scheduler thread.
            metrics.record("session-expiry", "unavailable");
            LOGGER.warn("Media session expiry pass unavailable; retrying on the next poll");
        }
    }
}
