package com.lifeos.media.api;

import com.lifeos.media.domain.MediaSessionKind;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/** Full versioned replacement of the schedule fields allowed while a session remains scheduled. */
public record UpdateMediaSessionRequest(
        @NotNull MediaSessionKind kind,
        @NotBlank @Size(max = 140) String title,
        @NotNull Instant scheduledStartAt,
        @NotNull Instant scheduledEndAt,
        @NotBlank @Size(max = 64) String timeZone) {
}
