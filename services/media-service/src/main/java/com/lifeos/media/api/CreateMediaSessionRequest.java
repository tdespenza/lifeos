package com.lifeos.media.api;

import com.lifeos.media.domain.MediaSessionKind;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/** Owner-scheduled session inputs. External participant invitations and SFU policy remain future work. */
public record CreateMediaSessionRequest(
        @NotNull MediaSessionKind kind,
        @NotBlank @Size(max = 140) String title,
        @NotNull Instant scheduledStartAt,
        @NotNull Instant scheduledEndAt,
        @NotBlank @Size(max = 64) String timeZone) {
}
