package com.lifeos.media.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/** Explicit user confirmation of one extracted post-session action item. */
public record ConfirmSessionActionRequest(
        @NotBlank @Size(max = 255) String actionItem,
        @Min(0) @Max(4) Integer priority,
        Instant dueAt) { }
