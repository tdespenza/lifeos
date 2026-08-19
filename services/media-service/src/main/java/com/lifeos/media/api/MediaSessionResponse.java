package com.lifeos.media.api;

import com.lifeos.media.domain.MediaSession;
import com.lifeos.media.domain.MediaSessionKind;
import com.lifeos.media.domain.MediaSessionStatus;
import java.time.Instant;
import java.util.UUID;

/** Public owner-scoped scheduled-session representation. */
public record MediaSessionResponse(
        UUID id,
        MediaSessionKind kind,
        String title,
        Instant scheduledStartAt,
        Instant scheduledEndAt,
        String timeZone,
        MediaSessionStatus status,
        long version,
        long remainingSeconds,
        boolean endWarning) {

    public static MediaSessionResponse from(MediaSession session) {
        return from(session, Instant.now());
    }

    public static MediaSessionResponse from(MediaSession session, Instant now) {
        long remainingSeconds = Math.max(0, java.time.Duration.between(now, session.getScheduledEndAt()).toSeconds());
        boolean endWarning = session.getStatus() == MediaSessionStatus.SCHEDULED
                && remainingSeconds > 0
                && remainingSeconds <= 60;
        return new MediaSessionResponse(
                session.getId(),
                session.getKind(),
                session.getTitle(),
                session.getScheduledStartAt(),
                session.getScheduledEndAt(),
                session.getTimeZone(),
                session.effectiveStatus(now),
                session.getVersion(),
                remainingSeconds,
                endWarning);
    }
}
