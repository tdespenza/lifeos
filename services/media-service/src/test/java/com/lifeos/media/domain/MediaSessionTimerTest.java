package com.lifeos.media.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MediaSessionTimerTest {

    @Test
    void exposesWarningAndEndsAtScheduledDeadlineWithoutExtendingTheWindow() {
        Instant start = Instant.parse("2026-01-01T10:00:00Z");
        Instant end = start.plusSeconds(300);
        MediaSession session = MediaSession.scheduled(
                UUID.randomUUID(), UUID.randomUUID(), "tenant", MediaSessionKind.COACHING,
                "Coaching", start, end, "UTC", start.minusSeconds(30));

        var warning = com.lifeos.media.api.MediaSessionResponse.from(session, end.minusSeconds(30));
        var ended = com.lifeos.media.api.MediaSessionResponse.from(session, end);

        assertThat(warning.status()).isEqualTo(MediaSessionStatus.SCHEDULED);
        assertThat(warning.remainingSeconds()).isEqualTo(30);
        assertThat(warning.endWarning()).isTrue();
        assertThat(ended.status()).isEqualTo(MediaSessionStatus.ENDED);
        assertThat(ended.remainingSeconds()).isZero();
        assertThat(session.isJoinableAt(end)).isFalse();
    }

    @Test
    void durableExpiryEndsOnlyScheduledSessionsAndIsIdempotent() {
        Instant start = Instant.parse("2026-01-01T10:00:00Z");
        Instant end = start.plusSeconds(3600);
        MediaSession session = MediaSession.scheduled(
                UUID.randomUUID(), UUID.randomUUID(), "tenant", MediaSessionKind.COACHING,
                "Session", start, end, "UTC", start);

        assertThat(session.endIfDue(end.minusNanos(1))).isFalse();
        assertThat(session.endIfDue(end)).isTrue();
        assertThat(session.getStatus()).isEqualTo(MediaSessionStatus.ENDED);
        assertThat(session.endIfDue(end.plusSeconds(1))).isFalse();
    }
}
