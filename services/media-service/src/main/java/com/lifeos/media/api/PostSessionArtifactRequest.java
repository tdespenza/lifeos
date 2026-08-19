package com.lifeos.media.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Bounded local post-session input; raw audio is never accepted through this JSON endpoint. */
public record PostSessionArtifactRequest(
        @NotBlank @Size(max = 65_536) String transcript) {
}
